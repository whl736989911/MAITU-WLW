package com.fireplatform.deviceservice;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Shared integration-driver boundary. A driver only transports a declared operation; it never owns
 * local device state, authorization, task state, or notification side effects. Drivers are called
 * outside any database transaction and must return an honest outcome even when the peer response is
 * unknown.
 */
public interface IntegrationDriver {
    String key();
    /** PRODUCT_MODEL or VENDOR_PLATFORM. */
    String mode();
    String displayName();
    DriverConfiguration configuration();
    List<DriverServiceDescriptor> services();
    DriverOutcome submit(DriverCall call);
    DriverOutcome query(DriverCall call);

    /** Immutable external truth snapshot the driver was configured from; null when unconfigured. */
    String accountFingerprint();

    default boolean handles(String serviceKey) {
        return services().stream().anyMatch(service -> service.key().equals(serviceKey));
    }

    /** True only for vendor adapters whose origin/account are edited through the legacy source card. */
    default boolean configurableOriginAccount() { return false; }

    record DriverConfiguration(boolean configured, boolean enabled, String code, String endpoint,
                               String tokenMasked, String accountFingerprint, long revision) { }

    record DriverServiceDescriptor(String key, String displayName, String category, String riskLevel,
                                   String resultKind, JsonNode inputSchema, JsonNode applicability,
                                   JsonNode evidence, String supportState, boolean typedNormalAllowed) { }

    record DriverCall(String commandId, String tenantId, String deviceId, String deviceEui,
                      String externalDeviceId, String externalSerial, String model, String productVersionId,
                      String reference, JsonNode arguments, DriverServiceDescriptor service,
                      String accountFingerprint) { }

    /**
     * outcome: ACCEPTED (vendor accepted, effect not proven), REJECTED (vendor refused),
     * UNKNOWN (request may or may not have been processed), SUCCEEDED/FAILED (documented terminal
     * evidence), BLOCKED (no external call was made because configuration/evidence is unavailable).
     */
    record DriverOutcome(String outcome, String vendorCode, String reference, JsonNode result, String note) {
        public static DriverOutcome accepted(String code, String reference, JsonNode result, String note) {
            return new DriverOutcome("ACCEPTED", code, reference, result, note);
        }
        public static DriverOutcome rejected(String code, JsonNode result, String note) {
            return new DriverOutcome("REJECTED", code, null, result, note);
        }
        public static DriverOutcome unknown(String code, JsonNode result, String note) {
            return new DriverOutcome("UNKNOWN", code, null, result, note);
        }
        public static DriverOutcome succeeded(String code, JsonNode result, String note) {
            return new DriverOutcome("SUCCEEDED", code, null, result, note);
        }
        public static DriverOutcome failed(String code, JsonNode result, String note) {
            return new DriverOutcome("FAILED", code, null, result, note);
        }
        public static DriverOutcome blocked(String code, String note) {
            return new DriverOutcome("BLOCKED", code, null, null, note);
        }
    }
}
