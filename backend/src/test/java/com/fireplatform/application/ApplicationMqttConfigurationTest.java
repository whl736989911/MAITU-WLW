package com.fireplatform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fireplatform.shared.ApiError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Local configuration contract for MQTT outputs: the target is normalized to one fixed TLS endpoint, the topic is
 * part of the frozen digest, HTTP credentials can never be attached to an MQTT output, and the topic/username/password
 * round-trip only through their own AES-GCM scope. No broker, DNS or network call is involved.
 */
class ApplicationMqttConfigurationTest {
    private static final String HOST = "broker.example.test";
    private static final String ORIGIN = "mqtts://" + HOST + ":8883";

    @Test
    void mqttTargetIsNormalizedToTheFixedTlsPortAndTheTopicIsFrozenIntoTheDigest() {
        var policy = policy(HOST);

        var validated = policy.validateMqtt("mqtts://Broker.Example.Test", " fire/alarm ");
        assertThat(validated.url()).isEqualTo(ORIGIN);
        assertThat(validated.origin()).isEqualTo(ORIGIN);
        assertThat(validated.host()).isEqualTo(HOST);
        assertThat(validated.masked()).isEqualTo(ORIGIN + "/***");
        assertThat(validated.digest()).isEqualTo(ApplicationEndpointPolicy.mqttDigest(ORIGIN, "fire/alarm"));

        // An explicit default port and a bare "/" path are the same target; a different topic is a different claim.
        assertThat(policy.validateMqtt("mqtts://" + HOST + ":8883/", "fire/alarm").digest())
            .isEqualTo(validated.digest());
        assertThat(policy.validateMqtt("mqtts://" + HOST, "fire/other").digest()).isNotEqualTo(validated.digest());

        // HTTPS keeps its historical digest of the exact URL, and it never collides with an MQTT claim.
        var https = policy.validate("https://" + HOST + "/hook");
        assertThat(https.digest()).isEqualTo(sha256("https://" + HOST + "/hook"));
        assertThat(https.digest()).isNotEqualTo(validated.digest());
    }

    @Test
    void mqttRefusesInsecurePrivateNonDefaultAndCredentialBearingTargets() {
        var policy = policy(HOST);
        assertCode(() -> policy.validateMqtt("mqtt://" + HOST, "fire/alarm"), "ENDPOINT_NOT_MQTTS");
        assertCode(() -> policy.validateMqtt("mqtts://" + HOST + ":1883", "fire/alarm"), "ENDPOINT_PORT_FORBIDDEN");
        assertCode(() -> policy.validateMqtt("mqtts://user:pass@" + HOST, "fire/alarm"),
            "ENDPOINT_USERINFO_FORBIDDEN");
        assertCode(() -> policy.validateMqtt("mqtts://" + HOST + "/sink", "fire/alarm"), "ENDPOINT_PATH_FORBIDDEN");
        assertCode(() -> policy.validateMqtt("mqtts://" + HOST + "?x=1", "fire/alarm"), "ENDPOINT_QUERY_FORBIDDEN");
        assertCode(() -> policy.validateMqtt("mqtts://" + HOST + "#fragment", "fire/alarm"),
            "ENDPOINT_FRAGMENT_FORBIDDEN");
        assertCode(() -> policy.validateMqtt("mqtts://other.example.test", "fire/alarm"), "ENDPOINT_HOST_NOT_ALLOWED");
        assertCode(() -> policy.validateMqtt("mqtts://10.0.0.1", "fire/alarm"), "ENDPOINT_PRIVATE_ADDRESS");
        assertCode(() -> policy.validateMqtt("mqtts://" + HOST, "fire/#"), "ENDPOINT_TOPIC_INVALID");
        assertCode(() -> policy.validateMqtt("mqtts://" + HOST, "fire/+"), "ENDPOINT_TOPIC_INVALID");
        assertCode(() -> policy.validateMqtt("mqtts://" + HOST, null), "ENDPOINT_TOPIC_INVALID");
        assertCode(() -> policy("").validateMqtt("mqtts://" + HOST, "fire/alarm"),
            "ENDPOINT_ALLOWLIST_NOT_CONFIGURED");
    }

    @Test
    void typedOutputConfigSeparatesMqttFromHttpMaterial() {
        var mqtt = ApplicationInputs.createOutput(body("MQTT_TLS_3_1_1", null, null, null, " fire/alarm ",
            "device-1", "secret-pass", 8_000L, false));
        assertThat(mqtt.spec().protocol()).isEqualTo("MQTT_TLS_3_1_1");
        assertThat(mqtt.spec().authScheme()).isEqualTo("NONE");
        assertThat(mqtt.spec().authHeaderName()).isNull();
        assertThat(mqtt.spec().credential()).isNull();
        assertThat(mqtt.spec().mqttTopic()).isEqualTo("fire/alarm");
        assertThat(mqtt.spec().mqttUsername()).isEqualTo("device-1");
        assertThat(mqtt.spec().mqttPassword()).isEqualTo("secret-pass");
        assertThat(mqtt.spec().timeoutMs()).isEqualTo(8_000);
        assertThat(mqtt.spec().enabled()).isFalse();

        // An HTTP scheme, header or credential is never silently dropped on an MQTT output.
        assertCode(() -> ApplicationInputs.createOutput(body("MQTT_TLS_3_1_1", "BEARER", null, "token", "fire/a",
            "user", "pass", null, true)), "INVALID_REQUEST");
        assertCode(() -> ApplicationInputs.createOutput(body("MQTT_TLS_3_1_1", null, null, "token", "fire/a",
            "user", "pass", null, true)), "INVALID_REQUEST");
        assertCode(() -> ApplicationInputs.createOutput(body("MQTT_TLS_3_1_1", null, "X-Token", null, "fire/a",
            "user", "pass", null, true)), "INVALID_REQUEST");
        assertCode(() -> ApplicationInputs.createOutput(body("MQTT_TLS_3_1_1", null, null, null, "fire/#",
            "user", "pass", null, true)), "INVALID_REQUEST");
        assertCode(() -> ApplicationInputs.createOutput(body("MQTT_TLS_3_1_1", null, null, null, "fire/a",
            "user\nname", "pass", null, true)), "INVALID_REQUEST");
        assertCode(() -> ApplicationInputs.createOutput(body("MQTT_TLS_3_1_1", null, null, null, "fire/a",
            "user", "pass", 999L, true)), "INVALID_REQUEST");
        assertCode(() -> ApplicationInputs.createOutput(body("MQTT_TLS_3_1_1", null, null, null, "fire/a",
            "user", "pass", 30_001L, true)), "INVALID_REQUEST");
        assertCode(() -> ApplicationInputs.createOutput(body("HTTPS", null, null, null, "fire/a",
            null, null, null, false)), "INVALID_REQUEST");

        // Protocol is immutable: an update may restate it but never switch it.
        assertCode(() -> ApplicationInputs.updateOutput(new ApplicationInputs.UpdateOutputBody("Output", "HTTPS",
            null, null, null, null, null, null, null, null, null, 0L, "reason"),
            "NORMALIZED", "MQTT_TLS_3_1_1"), "INVALID_REQUEST");
        var restated = ApplicationInputs.updateOutput(new ApplicationInputs.UpdateOutputBody("Output",
            "MQTT_TLS_3_1_1", null, null, null, null, "fire/a", "user", "pass", 5_000L, false, 0L,
            "reason"), "NORMALIZED", "MQTT_TLS_3_1_1");
        assertThat(restated.spec().protocol()).isEqualTo("MQTT_TLS_3_1_1");
    }

    @Test
    void mqttSecretsRoundTripOnlyInsideTheirOwnEncryptedScope() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.application-output-key",
            Base64.getEncoder().encodeToString(new byte[32]));
        var secrets = new ApplicationSecretStorage(environment);
        assertThat(secrets.ready()).isTrue();

        var topic = secrets.protect(ApplicationSecretStorage.mqttTopicScope("unit", "output"), "fire/alarm");
        var username = secrets.protect(ApplicationSecretStorage.mqttUsernameScope("unit", "output"), "device-1");
        var password = secrets.protect(ApplicationSecretStorage.mqttPasswordScope("unit", "output"), "secret-pass");
        assertThat(topic.cipher()).isNotEqualTo("fire/alarm");
        assertThat(username.cipher()).isNotEqualTo("device-1");
        assertThat(password.cipher()).isNotEqualTo("secret-pass");

        assertThat(secrets.reveal(ApplicationSecretStorage.mqttTopicScope("unit", "output"), topic.cipher(),
            topic.nonce())).isEqualTo("fire/alarm");
        assertThat(secrets.reveal(ApplicationSecretStorage.mqttUsernameScope("unit", "output"), username.cipher(),
            username.nonce())).isEqualTo("device-1");
        assertThat(secrets.reveal(ApplicationSecretStorage.mqttPasswordScope("unit", "output"), password.cipher(),
            password.nonce())).isEqualTo("secret-pass");

        // The scope is authenticated: another output or another field cannot decrypt this ciphertext.
        assertCode(() -> secrets.reveal(ApplicationSecretStorage.mqttTopicScope("unit", "other"), topic.cipher(),
            topic.nonce()), "APPLICATION_OUTPUT_ENCRYPTION_UNAVAILABLE");
        assertCode(() -> secrets.reveal(ApplicationSecretStorage.mqttPasswordScope("unit", "output"), topic.cipher(),
            topic.nonce()), "APPLICATION_OUTPUT_ENCRYPTION_UNAVAILABLE");

        var unready = new ApplicationSecretStorage(new MockEnvironment());
        assertThat(unready.ready()).isFalse();
        assertCode(() -> unready.protect(ApplicationSecretStorage.mqttTopicScope("unit", "output"), "fire/alarm"),
            "APPLICATION_OUTPUT_ENCRYPTION_UNAVAILABLE");
    }

    private static ApplicationInputs.CreateOutputBody body(String protocol, String authScheme, String header,
                                                           String credential, String topic, String username,
                                                           String password, Long timeout, Boolean enabled) {
        return new ApplicationInputs.CreateOutputBody("Output", "NORMALIZED", protocol, null, authScheme, header,
            credential, topic, username, password, timeout, enabled, "reason");
    }

    private static ApplicationEndpointPolicy policy(String allowlist) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.application-output-allowed-hosts", allowlist);
        return new ApplicationEndpointPolicy(environment);
    }

    @FunctionalInterface
    private interface Call {
        void run();
    }

    private static void assertCode(Call call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(ApiError.class)
            .hasFieldOrPropertyWithValue("code", code);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
