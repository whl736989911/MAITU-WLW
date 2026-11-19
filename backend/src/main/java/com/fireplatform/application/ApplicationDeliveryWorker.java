package com.fireplatform.application;

import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.shared.ApiError;
import com.fireplatform.transport.ApplicationMqttTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Delivery loop. Enabled only by deployment configuration and the worker role, both default off. A pass claims at
 * most a bounded batch and performs at most one real outbound exchange (HTTPS POST or MQTT TLS publish) per claim;
 * every outcome is committed through the processor, so a crash cannot lose the record of an external effect.
 */
@Component
public class ApplicationDeliveryWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ApplicationDeliveryWorker.class);

    private final RuntimeConfig config;
    private final ApplicationDeliveryProcessor processor;
    private final ApplicationDeliveryTransport transport;
    private final ApplicationMqttTransport mqttTransport;
    private final ApplicationSecretStorage secrets;
    private final ApplicationEndpointPolicy endpoints;
    private final ApplicationAvailability availability;
    private volatile boolean running;
    private Thread thread;

    public ApplicationDeliveryWorker(RuntimeConfig config, ApplicationDeliveryProcessor processor,
                                     ApplicationDeliveryTransport transport,
                                     ApplicationMqttTransport mqttTransport,
                                     ApplicationSecretStorage secrets,
                                     ApplicationEndpointPolicy endpoints, ApplicationAvailability availability) {
        this.config = config;
        this.processor = processor;
        this.transport = transport;
        this.mqttTransport = mqttTransport;
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.availability = availability;
    }

    @Override
    public synchronized void start() {
        if (running || !config.applicationOutputWorkerEnabled()) return;
        running = true;
        try {
            int recovered = processor.recoverInterrupted();
            if (recovered > 0) log.info("application output recovered {} interrupted deliveries as UNKNOWN", recovered);
        } catch (RuntimeException failure) {
            log.warn("application output recovery pass failed");
        }
        thread = new Thread(this::loop, "application-output-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            if (availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC) {
                try {
                    int processed = 0;
                    while (running && processed < config.applicationOutputBatchSize()) {
                        var claim = processor.claim();
                        if (claim == null) break;
                        dispatch(claim);
                        processed++;
                    }
                } catch (RuntimeException failure) {
                    log.warn("application output delivery pass failed");
                }
            }
            try {
                Thread.sleep(config.applicationOutputPollMs());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Preparation failures block without I/O; only a fully prepared request reaches a transport. */
    private void dispatch(ApplicationDeliveryProcessor.Claim claim) {
        ApplicationDeliveryTransport.Outcome outcome;
        String gate = revisionGate(claim);
        if (gate != null) {
            // Defence in depth at the send boundary: the claim already refused this, so a regression cannot send.
            processor.finish(claim, ApplicationDeliveryTransport.Outcome.blocked(gate));
            return;
        }
        try {
            outcome = "MQTT_TLS_3_1_1".equals(claim.protocol()) ? dispatchMqtt(claim) : dispatchHttps(claim);
        } catch (ApiError refusal) {
            outcome = ApplicationDeliveryTransport.Outcome.blocked(refusal.code);
        } catch (RuntimeException unexpected) {
            outcome = ApplicationDeliveryTransport.Outcome.blocked("DELIVERY_PREPARATION_FAILED");
        }
        try {
            processor.finish(claim, outcome);
        } catch (RuntimeException failure) {
            // The row stays IN_FLIGHT; startup reconciliation records UNKNOWN after the bounded window.
            log.warn("application output result commit failed");
        }
    }

    private ApplicationDeliveryTransport.Outcome dispatchHttps(ApplicationDeliveryProcessor.Claim claim) {
        String url = secrets.reveal(
            ApplicationSecretStorage.urlScope(claim.tenantId(), claim.outputId()),
            claim.urlCipher(), claim.urlNonce());
        var validated = endpoints.validate(url);
        if (!validated.digest().equals(claim.endpointDigest()))
            return ApplicationDeliveryTransport.Outcome.blocked("ENDPOINT_CHANGED");
        String credential = "NONE".equals(claim.authScheme()) ? null : secrets.reveal(
            ApplicationSecretStorage.credentialScope(claim.tenantId(), claim.outputId()),
            claim.credentialCipher(), claim.credentialNonce());
        return transport.post(validated.url(), claim.contentType(), claim.payload(), claim.authScheme(),
            claim.authHeader(), credential, claim.timeoutMs(), claim.outboxId());
    }

    /**
     * MQTT reveals the frozen topic/username/password only after re-validating the target and recomputing the
     * frozen digest. The original hostname is pinned to one public address; TLS still verifies the hostname.
     */
    private ApplicationDeliveryTransport.Outcome dispatchMqtt(ApplicationDeliveryProcessor.Claim claim) {
        String url = secrets.reveal(
            ApplicationSecretStorage.urlScope(claim.tenantId(), claim.outputId()),
            claim.urlCipher(), claim.urlNonce());
        String topic = secrets.reveal(
            ApplicationSecretStorage.mqttTopicScope(claim.tenantId(), claim.outputId()),
            claim.mqttTopicCipher(), claim.mqttTopicNonce());
        var validated = endpoints.validateMqtt(url, topic);
        if (!validated.digest().equals(claim.endpointDigest()))
            return ApplicationDeliveryTransport.Outcome.blocked("ENDPOINT_CHANGED");
        String username = secrets.reveal(
            ApplicationSecretStorage.mqttUsernameScope(claim.tenantId(), claim.outputId()),
            claim.mqttUsernameCipher(), claim.mqttUsernameNonce());
        String password = secrets.reveal(
            ApplicationSecretStorage.mqttPasswordScope(claim.tenantId(), claim.outputId()),
            claim.mqttPasswordCipher(), claim.mqttPasswordNonce());
        var address = endpoints.pin(validated.host());
        var result = mqttTransport.publish(address, validated.host(), topic, username, password, claim.payload(),
            claim.timeoutMs(), claim.outboxId());
        if ("BLOCKED".equals(result.status()))
            return ApplicationDeliveryTransport.Outcome.blocked(result.failureCode());
        return new ApplicationDeliveryTransport.Outcome(result.status(), null, result.failureCode());
    }

    /**
     * Same fail-closed rule as the claim: a granted decoder/adapter digest must be matched by the fact's digest.
     * Null fact digest is unknown evidence (prerequisite), a different digest is a mismatch.
     */
    private static String revisionGate(ApplicationDeliveryProcessor.Claim claim) {
        if ("FORMAL_VERSION".equals(claim.referenceKind())) return null;
        if (claim.frozenDigest() == null || claim.factDigest() == null) return "IDENTITY_PREREQUISITE_MISSING";
        if (!claim.frozenDigest().equalsIgnoreCase(claim.factDigest())) return "IDENTITY_MISMATCH";
        return null;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() { return running; }
}
