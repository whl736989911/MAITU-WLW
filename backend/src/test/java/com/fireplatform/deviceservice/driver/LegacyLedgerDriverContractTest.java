package com.fireplatform.deviceservice.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.deviceservice.IntegrationDriver.DriverCall;
import com.fireplatform.deviceservice.IntegrationDriver.DriverOutcome;
import com.fireplatform.deviceservice.IntegrationDriver.DriverServiceDescriptor;
import com.fireplatform.deviceservice.LegacySourceConfigService;
import com.fireplatform.deviceservice.VendorLifecycleDriver;
import com.fireplatform.transport.PinnedHttpTransport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Deterministic wire-contract tests for the real TTL/TSF adapters. They do not call any live legacy
 * service: the lower transport seam captures the exact request and returns recorded protocol shapes.
 */
class LegacyLedgerDriverContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TTL_ORIGIN = "http://192.0.2.10:85";
    private static final String TSF_ORIGIN = "http://192.0.2.10:85";

    @Test
    void ttlCommandVerifiesEuiAndSendsCallerRecoveredDIdPayload() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "ttl-token")));
        transport.reply(ok(200, detail("9001", "EUI-9001", "platform-9001")));
        transport.reply(ok(200, JSON.createObjectNode().put("tx_id", "tx-1")));
        LegacyTtlDriver driver = new LegacyTtlDriver(source(ttlConfig(true)), JSON, transport);

        DriverOutcome outcome = driver.submit(call(driver, LegacyTtlDriver.COMMAND_SEND, "9001", "EUI-9001",
            JSON.createObjectNode().put("command", "{\"buzzerSilenceTime\":\"30\"}")
                .put("serviceName", "controlBuzzerSilenceTime").put("expireTime", 5)));

        assertThat(outcome.outcome()).isEqualTo("ACCEPTED");
        assertThat(outcome.reference()).isEqualTo("tx-1");
        assertThat(transport.calls).hasSize(3);
        assertThat(transport.calls.get(0).path()).isEqualTo("/api/login");
        assertThat(transport.calls.get(0).headers()).doesNotContainKey("Tur-Authorization");
        assertThat(transport.calls.get(1).path()).isEqualTo("/api/iot/device/9001");
        assertThat(transport.calls.get(1).headers()).containsEntry("Tur-Authorization", "Bearer ttl-token");
        assertThat(transport.calls.get(2).path()).isEqualTo("/api/iot/device/createAnalysisCommand");
        assertThat(transport.calls.get(2).headers()).containsEntry("Tur-Authorization", "Bearer ttl-token");
        JsonNode body = JSON.readTree(transport.calls.get(2).body());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("expireTime", "command", "serviceName", "dParamList", "deviceId");
        assertThat(body.path("deviceId").isNumber()).isTrue();
        assertThat(body.path("deviceId").asLong()).isEqualTo(9001L);
        assertThat(body.path("expireTime").asInt()).isEqualTo(5);
        assertThat(body.path("serviceName").asText()).isEqualTo("controlBuzzerSilenceTime");
        assertThat(body.path("dParamList").isArray()).isTrue();
        assertThat(body.path("dParamList").size()).isZero();
    }

    @Test
    void ttlCommandDoesNotSendWhenRemoteEuiDrifted() {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "ttl-token")));
        transport.reply(ok(200, detail("9001", "EUI-OTHER", "platform-9001")));
        LegacyTtlDriver driver = new LegacyTtlDriver(source(ttlConfig(true)), JSON, transport);

        DriverOutcome outcome = driver.submit(call(driver, LegacyTtlDriver.COMMAND_SEND, "9001", "EUI-9001",
            JSON.createObjectNode().put("command", "{}")));

        assertThat(outcome.outcome()).isEqualTo("FAILED");
        assertThat(outcome.vendorCode()).isEqualTo("SOURCE_IDENTITY_UNCONFIRMED");
        assertThat(transport.calls).hasSize(2);
        assertThat(transport.calls).noneMatch(call -> call.path().endsWith("createAnalysisCommand"));
    }

    @Test
    void tsfCommandUsesVerifiedDDeviceIdAndExactCallerFields() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "tsf-token")));
        transport.reply(ok(200, detail("7001", "EUI-7001", "platform-uuid-7001")));
        transport.reply(ok(200, JSON.createObjectNode()));
        LegacyTsfDriver driver = new LegacyTsfDriver(source(tsfConfig(true)), JSON, transport);

        DriverOutcome outcome = driver.submit(call(driver, LegacyTsfDriver.COMMAND_SEND, "7001", "EUI-7001",
            JSON.createObjectNode().put("command", "{\"buzzerSilenceTime\":\"30\"}")
                .put("serviceName", "controlBuzzerSilenceTime").put("expireTime", 1)));

        assertThat(outcome.outcome()).isEqualTo("ACCEPTED");
        assertThat(transport.calls.get(1).path()).isEqualTo("/api/tsf/device/7001");
        assertThat(transport.calls.get(2).path()).isEqualTo("/api/tsf/device/createAnalysisCommand");
        assertThat(transport.calls.get(2).headers()).containsEntry("Authorization", "Bearer tsf-token");
        JsonNode body = JSON.readTree(transport.calls.get(2).body());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("command", "deviceId", "expireTime", "serviceName");
        assertThat(body.path("deviceId").asText()).isEqualTo("platform-uuid-7001");
    }

    @Test
    void tsfCommandStopsWhenPlatformDeviceIdMissing() {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "tsf-token")));
        transport.reply(ok(200, detail("7001", "EUI-7001", null)));
        LegacyTsfDriver driver = new LegacyTsfDriver(source(tsfConfig(true)), JSON, transport);

        DriverOutcome outcome = driver.submit(call(driver, LegacyTsfDriver.COMMAND_SEND, "7001", "EUI-7001",
            JSON.createObjectNode().put("command", "{}")));

        assertThat(outcome.outcome()).isEqualTo("FAILED");
        assertThat(outcome.vendorCode()).isEqualTo("IDENTIFIER_UNAVAILABLE");
        assertThat(transport.calls).hasSize(2);
    }

    @Test
    void lookupUsesExactDIdDetailAndNeverFallsBackToFirstRow() {
        FakeTransport transport = new FakeTransport();
        LegacyTtlDriver driver = new LegacyTtlDriver(source(ttlConfig(true)), JSON, transport);

        VendorLifecycleDriver.LookupResult noId = driver.lookup("EUI-9001", null);
        assertThat(noId.state()).isEqualTo("UNKNOWN");
        assertThat(transport.calls).isEmpty();

        transport.reply(ok(200, JSON.createObjectNode().put("token", "ttl-token")));
        transport.reply(ok(200, detail("9001", "EUI-9001", "platform-9001")));
        VendorLifecycleDriver.LookupResult found = driver.lookup("EUI-9001", "9001");
        assertThat(found.state()).isEqualTo("FOUND");
        assertThat(found.externalDeviceId()).isEqualTo("9001");
        assertThat(found.result().path("remote_lookup").asBoolean()).isTrue();
        assertThat(found.result().path("identity_verified").asBoolean()).isTrue();
        assertThat(transport.calls).extracting(Call::path)
            .containsExactly("/api/login", "/api/iot/device/9001");
    }

    @Test
    void tsfLookupRejectsDifferentEui() {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "tsf-token")));
        transport.reply(ok(200, detail("7001", "EUI-OTHER", "platform-uuid-7001")));
        LegacyTsfDriver driver = new LegacyTsfDriver(source(tsfConfig(true)), JSON, transport);

        VendorLifecycleDriver.LookupResult result = driver.lookup("EUI-7001", "7001");

        assertThat(result.state()).isEqualTo("NOT_FOUND");
        assertThat(result.result().path("identity_verified").asBoolean()).isFalse();
    }

    @Test
    void ttlTxAcceptanceIsNeverTerminalExecution() {
        assertThat(ttlTxOutcome(txRow(1, 200)).outcome()).isEqualTo("ACCEPTED");
        assertThat(ttlTxOutcome(txRow(1, 200)).vendorCode()).isEqualTo("TX_VENDOR_ACK");
        assertThat(ttlTxOutcome(txRow(1, 400)).outcome()).isEqualTo("UNKNOWN");
        assertThat(ttlTxOutcome(txRow(1, null)).outcome()).isEqualTo("ACCEPTED");
        assertThat(ttlTxOutcome(txRow(1, null)).vendorCode()).isEqualTo("TX_CHANNEL_ACCEPTED");
        assertThat(ttlTxOutcome(txRow(0, null)).outcome()).isEqualTo("UNKNOWN");
        assertThat(ttlTxOutcome(txRow(0, null)).vendorCode()).isEqualTo("TX_CHANNEL_FAILED");
        assertThat(ttlTxOutcome(JSON.createObjectNode()).outcome()).isEqualTo("UNKNOWN");
    }

    @Test
    void ttlTxObjectCannotUseAnotherTransactionsAcknowledgement() {
        for (String referenceField : java.util.List.of("tx_id", "txId")) {
            DriverOutcome outcome = ttlTxOutcome(txRow(1, 200).put(referenceField, "tx-other"));

            assertThat(outcome.outcome()).isEqualTo("UNKNOWN");
            assertThat(outcome.result()).isNull();
        }
    }

    @Test
    void cloudDeletionReadConfirmsOnlyExactLedgerAbsenceWithoutDeletingAgain() {
        for (boolean ttl : java.util.List.of(true, false)) {
            assertThat(cloudDeletionQuery(ttl, api(404, 200, JSON.nullNode())).outcome()).isEqualTo("SUCCEEDED");
            DriverOutcome absent = cloudDeletionQuery(ttl, api(200, 404, JSON.nullNode()));
            assertThat(absent.outcome()).isEqualTo("SUCCEEDED");
            assertThat(absent.result().path("ledger_absence_confirmed").asBoolean()).isTrue();
        }
    }

    @Test
    void cloudDeletionReadDoesNotConfusePresentMissingIdentityOrDeniedWithAbsence() {
        for (boolean ttl : java.util.List.of(true, false)) {
            for (PinnedHttpTransport.Response response : java.util.List.of(
                ok(200, detail("9001", "EUI-9001", null)),
                ok(200, JSON.nullNode()),
                api(403, 403, JSON.nullNode()),
                api(500, 404, JSON.nullNode()))) {
                assertThat(cloudDeletionQuery(ttl, response).outcome()).isEqualTo("UNKNOWN");
            }
        }
    }

    @Test
    void cloudDeletionReadDoesNotDiscloseAnotherDevicesIdentityAsItsResult() {
        for (boolean ttl : java.util.List.of(true, false)) {
            DriverOutcome outcome = cloudDeletionQuery(ttl, ok(200, detail("9001", "EUI-OTHER", null)));
            assertThat(outcome.outcome()).isEqualTo("UNKNOWN");
            assertThat(outcome.result()).isNull();
        }
    }

    @Test
    void cloudDeletionReadNeverContactsADisabledSource() {
        for (boolean ttl : java.util.List.of(true, false)) {
            FakeTransport transport = new FakeTransport();
            var config = new LegacySourceConfigService.Config(true, false, "DISABLED", TTL_ORIGIN,
                "fixture-user", "fixture-password", true, "masked", "fp", 1);
            com.fireplatform.deviceservice.IntegrationDriver driver = ttl
                ? new LegacyTtlDriver(source(config), JSON, transport)
                : new LegacyTsfDriver(source(config), JSON, transport);
            DriverOutcome outcome = driver.query(call(driver.services(), "cloud.delete", "9001", "EUI-9001", JSON.createObjectNode()));
            assertThat(outcome.outcome()).isEqualTo("BLOCKED");
            assertThat(transport.calls).isEmpty();
        }
    }

    @Test
    void cachedDetailProjectionDropsSensitiveKeysEvenInsideJsonStrings() {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "ttl-token")));
        ObjectNode data = detail("9001", "EUI-9001", null)
            .put("dAttr", "{\"AppKey\":\"secret\"}")
            .put("imei", "secret-imei")
            .put("dParam", "{\"smoke\":1,\"password\":\"secret\"}");
        transport.reply(ok(200, data));
        LegacyTtlDriver driver = new LegacyTtlDriver(source(ttlConfig(true)), JSON, transport);

        DriverOutcome outcome = driver.submit(call(driver, LegacyTtlDriver.DETAIL_READ, "9001", "EUI-9001",
            JSON.createObjectNode()));

        assertThat(outcome.outcome()).isEqualTo("SUCCEEDED");
        assertThat(outcome.result().has("dAttr")).isFalse();
        assertThat(outcome.result().has("imei")).isFalse();
        assertThat(outcome.result().path("dParam").isTextual()).isTrue();
        assertThat(outcome.result().path("dParam").asText()).contains("\"smoke\":1").doesNotContain("password").doesNotContain("secret");
        assertThat(outcome.result().toString()).doesNotContain("secret");
    }

    @Test
    void ttlRegisterSendsDIdProductFieldsAndReturnsLedgerId() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "ttl-token")));
        transport.reply(ok(200, JSON.createObjectNode().put("dId", 9001)));
        LegacyTtlDriver driver = new LegacyTtlDriver(source(ttlConfig(true)), JSON, transport);
        ObjectNode payload = JSON.createObjectNode().put("productId", 11).put("applicationId", 12)
            .put("platformId", 13).put("networkModel", 1);
        var registration = new VendorLifecycleDriver.CloudRegistration("New smoke", "EUI-NEW-1", null, null, null,
            null, null, null, null, null, null, null, null, payload);

        VendorLifecycleDriver.CloudResult result = driver.register(registration, null);

        assertThat(result.outcome()).isEqualTo("SUCCEEDED");
        assertThat(result.externalDeviceId()).isEqualTo("9001");
        assertThat(transport.calls.get(1).path()).isEqualTo("/api/iot/device");
        JsonNode body = JSON.readTree(transport.calls.get(1).body());
        assertThat(body.path("dName").asText()).isEqualTo("New smoke");
        assertThat(body.path("dEui").asText()).isEqualTo("EUI-NEW-1");
        assertThat(body.path("dPid").asInt()).isEqualTo(11);
        assertThat(body.path("dAid").asInt()).isEqualTo(12);
        assertThat(body.path("dPlatform").asInt()).isEqualTo(13);
        assertThat(body.path("dNetworkModel").asInt()).isEqualTo(1);
    }

    @Test
    void tsfRegisterKeepsProductAndPlaceIdentifiersDistinct() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "tsf-token")));
        transport.reply(ok(200, JSON.createObjectNode().put("dId", 7001)));
        LegacyTsfDriver driver = new LegacyTsfDriver(source(tsfConfig(true)), JSON, transport);
        ObjectNode payload = JSON.createObjectNode().put("productId", 21).put("applicationId", 22)
            .put("platformId", 23).put("networkModel", 0).put("placeId", 31).put("node", 5)
            .put("address", "Building 1").put("gps", "120.1,30.2").put("childSystem", "fire");
        var registration = new VendorLifecycleDriver.CloudRegistration("New TSF smoke", "EUI-NEW-2", null, null, null,
            null, null, null, null, null, null, null, null, payload);

        VendorLifecycleDriver.CloudResult result = driver.register(registration, null);

        assertThat(result.outcome()).isEqualTo("SUCCEEDED");
        assertThat(result.externalDeviceId()).isEqualTo("7001");
        assertThat(transport.calls.get(1).path()).isEqualTo("/api/tsf/device");
        JsonNode body = JSON.readTree(transport.calls.get(1).body());
        assertThat(body.path("dProductId").asInt()).isEqualTo(21);
        assertThat(body.path("dPid").asInt()).isEqualTo(31);
        assertThat(body.path("dAid").asInt()).isEqualTo(22);
        assertThat(body.path("dPlatform").asInt()).isEqualTo(23);
        assertThat(body.path("dNetworkModel").asInt()).isEqualTo(0);
    }

    @Test
    void preflightReadTimeoutIsDefinitelyUnsent() {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "ttl-token")));
        transport.fail(new PinnedHttpTransport.Failure("NETWORK_ERROR", "来源网络请求未确认"));
        LegacyTtlDriver driver = new LegacyTtlDriver(source(ttlConfig(true)), JSON, transport);

        DriverOutcome outcome = driver.submit(call(driver, LegacyTtlDriver.COMMAND_SEND, "9001", "EUI-9001",
            JSON.createObjectNode().put("command", "{}")));

        assertThat(outcome.outcome()).isEqualTo("FAILED");
        assertThat(outcome.vendorCode()).isEqualTo("NETWORK_ERROR");
        assertThat(transport.calls).hasSize(2);
    }

    @Test
    void sentCommandWithNetworkFailureStaysUnknownAndIsNotRetried() {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "ttl-token")));
        transport.reply(ok(200, detail("9001", "EUI-9001", "platform-9001")));
        transport.fail(new PinnedHttpTransport.Failure("NETWORK_ERROR", "来源网络请求未确认"));
        LegacyTtlDriver driver = new LegacyTtlDriver(source(ttlConfig(true)), JSON, transport);

        DriverOutcome outcome = driver.submit(call(driver, LegacyTtlDriver.COMMAND_SEND, "9001", "EUI-9001",
            JSON.createObjectNode().put("command", "{}")));

        assertThat(outcome.outcome()).isEqualTo("UNKNOWN");
        assertThat(outcome.note()).contains("禁止自动重发");
        assertThat(transport.calls).hasSize(3);
    }

    @Test
    void plainHttpRequiresRecordedRiskAcknowledgementBeforeAnyNetworkIo() {
        LegacySourceConfigService.Config insecure = ttlConfig(false);
        LegacyApiClient client = new LegacyApiClient(JSON, insecure, "Tur-Authorization",
            PinnedHttpTransport::exchange);
        assertThatThrownBy(client::open).isInstanceOfSatisfying(LegacyApiClient.Failure.class,
            failure -> assertThat(failure.code()).isEqualTo("HTTP_NOT_ACKNOWLEDGED"));
    }

    private DriverOutcome ttlTxOutcome(ObjectNode data) {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "ttl-token")));
        transport.reply(ok(200, data));
        LegacyTtlDriver driver = new LegacyTtlDriver(source(ttlConfig(true)), JSON, transport);
        DriverServiceDescriptor descriptor = driver.services().stream()
            .filter(service -> service.key().equals(LegacyTtlDriver.COMMAND_SEND)).findFirst().orElseThrow();
        DriverCall query = new DriverCall("command-1", "tenant-a", "device-a", "EUI-9001", "9001", "EUI-9001",
            "model", null, "tx-1", JSON.createObjectNode(), descriptor, "fp");
        return driver.query(query);
    }

    private DriverOutcome cloudDeletionQuery(boolean ttl, PinnedHttpTransport.Response response) {
        FakeTransport transport = new FakeTransport();
        transport.reply(ok(200, JSON.createObjectNode().put("token", "fixture-token")));
        transport.reply(response);
        com.fireplatform.deviceservice.IntegrationDriver driver = ttl
            ? new LegacyTtlDriver(source(ttlConfig(true)), JSON, transport)
            : new LegacyTsfDriver(source(tsfConfig(true)), JSON, transport);
        DriverOutcome outcome = driver.query(call(driver.services(), "cloud.delete", "9001", "EUI-9001", JSON.createObjectNode()));
        assertThat(transport.calls).extracting(Call::method).containsExactly("POST", "GET");
        assertThat(transport.calls.get(1).path()).isEqualTo(ttl ? "/api/iot/device/9001" : "/api/tsf/device/9001");
        return outcome;
    }

    private static ObjectNode txRow(int stat, Integer resultCode) {
        ObjectNode row = JSON.createObjectNode().put("tx_stat", stat);
        if (resultCode != null) row.set("tx_result", JSON.createObjectNode().put("code", resultCode));
        return row;
    }

    private static DriverCall call(LegacyTtlDriver driver, String serviceKey, String externalId, String serial,
                                   ObjectNode arguments) {
        return call(driver.services(), serviceKey, externalId, serial, arguments);
    }

    private static DriverCall call(LegacyTsfDriver driver, String serviceKey, String externalId, String serial,
                                   ObjectNode arguments) {
        return call(driver.services(), serviceKey, externalId, serial, arguments);
    }

    private static DriverCall call(List<DriverServiceDescriptor> services, String serviceKey, String externalId,
                                   String serial, ObjectNode arguments) {
        DriverServiceDescriptor descriptor = services.stream()
            .filter(service -> service.key().equals(serviceKey)).findFirst().orElseThrow();
        return new DriverCall("command-1", "tenant-a", "device-a", serial, externalId, serial, "model", null,
            null, arguments, descriptor, "fingerprint");
    }

    private static LegacySourceConfigService source(LegacySourceConfigService.Config config) {
        LegacySourceConfigService source = mock(LegacySourceConfigService.class);
        when(source.load("LEGACY_TTL")).thenReturn(config);
        when(source.load("LEGACY_TSF")).thenReturn(config);
        return source;
    }

    private static LegacySourceConfigService.Config ttlConfig(boolean allowInsecureHttp) {
        return new LegacySourceConfigService.Config(true, true, "READY", TTL_ORIGIN, "ttl-user", "ttl-pass",
            allowInsecureHttp, "****er", "fp", 1);
    }

    private static LegacySourceConfigService.Config tsfConfig(boolean allowInsecureHttp) {
        return new LegacySourceConfigService.Config(true, true, "READY", TSF_ORIGIN, "tsf-user", "tsf-pass",
            allowInsecureHttp, "****er", "fp", 1);
    }

    private static ObjectNode detail(String id, String eui, String deviceId) {
        ObjectNode data = JSON.createObjectNode().put("dId", id).put("dEui", eui).put("dName", "smoke " + id);
        if (deviceId != null) data.put("dDeviceId", deviceId);
        return data;
    }

    private static PinnedHttpTransport.Response ok(int status, JsonNode data) {
        return api(status, 200, data);
    }

    private static PinnedHttpTransport.Response api(int status, int code, JsonNode data) {
        ObjectNode root = JSON.createObjectNode().put("code", code).put("message", "ok");
        root.set("data", data);
        return new PinnedHttpTransport.Response(status, Map.of("content-type", List.of("application/json")),
            root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    record Call(String method, String path, Map<String, String> headers, String body) { }

    private static final class FakeTransport implements LegacyApiClient.Transport {
        private final List<Call> calls = new ArrayList<>();
        private final Deque<Object> replies = new ArrayDeque<>();

        void reply(PinnedHttpTransport.Response response) { replies.addLast(response); }
        void fail(RuntimeException failure) { replies.addLast(failure); }

        @Override
        public PinnedHttpTransport.Response exchange(PinnedHttpTransport.Target target, String method, String path,
                                                     Map<String, String> headers, byte[] body) {
            calls.add(new Call(method, path, new LinkedHashMap<>(headers),
                body == null ? null : new String(body, StandardCharsets.UTF_8)));
            Object reply = replies.isEmpty()
                ? new PinnedHttpTransport.Failure("NO_SCRIPTED_REPLY", "test transport has no reply")
                : replies.removeFirst();
            if (reply instanceof RuntimeException failure) throw failure;
            return (PinnedHttpTransport.Response) reply;
        }
    }
}
