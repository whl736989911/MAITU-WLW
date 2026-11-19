package com.fireplatform.legacyingest.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.legacyingest.LegacyEventSink;
import com.fireplatform.legacyingest.LegacyNormalizedEvent;
import com.fireplatform.legacyingest.codec.LegacyDecodeService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
class LegacyRealtimeNormalizerRegressionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void capturedAepPaCodeBodiesKeepStableDeviceIdentityAndPendingDecoderState() throws Exception {
        for (String sample : List.of("323", "500")) {
            JsonNode vector = json.readTree(LegacyRealtimeNormalizerRegressionTest.class.getResourceAsStream(
                "/legacy-codecs/samples/" + sample + ".json"));
            String bodyJson = new String(java.util.HexFormat.of().parseHex(vector.path("payload_hex").asText()), StandardCharsets.UTF_8);
            JsonNode body = json.readTree(bodyJson);
            var parsed = new AepTurboNormalizer(json).parse(body, "aep_turbo", null);
            assertEquals(body.path("deviceId").asText(), parsed.vendorDeviceId());
            assertNotEquals(body.path("IMEI").asText(), parsed.vendorDeviceId());
            assertTrue(parsed.pendingDecoder());
            assertTrue(parsed.events().getFirst().paramsJson().contains("messageType"));
            assertTrue(parsed.events().getFirst().paramsJson().contains("APPdata") || sample.equals("323"));
            var service = new LegacyIngestService(null, json, null, null, List.of(), null);
            var payloadHex = LegacyIngestService.class.getDeclaredMethod("payloadHex", String.class, JsonNode.class);
            payloadHex.setAccessible(true);
            assertEquals(java.util.HexFormat.of().formatHex(json.writeValueAsBytes(body)),
                payloadHex.invoke(service, "aep_turbo", body));
        }
    }

    @Test void aepEventReportRetainsEventContentAndDoesNotInventKnownSemantics() throws Exception {
        JsonNode body = json.readTree("{\"protocol\":\"lwm2m\",\"deviceId\":\"stable-id\",\"IMEI\":\"module-id\",\"messageType\":\"eventReport\",\"timestamp\":1793342705,\"eventContent\":{\"smoke_value\":209}}");
        var parsed = new AepTurboNormalizer(json).parse(body, "aep_turbo", null);
        assertEquals("stable-id", parsed.vendorDeviceId());
        assertEquals(OffsetDateTime.parse("2026-10-30T06:45:05Z"), parsed.observedAt());
        assertTrue(parsed.pendingDecoder());
        assertEquals("OTHER", parsed.events().getFirst().kind());
        assertTrue(parsed.events().getFirst().paramsJson().contains("eventContent"));
        var service = new LegacyIngestService(null, json, null, null, List.of(), null);
        var payloadHex = LegacyIngestService.class.getDeclaredMethod("payloadHex", String.class, JsonNode.class);
        payloadHex.setAccessible(true);
        assertEquals(java.util.HexFormat.of().formatHex(json.writeValueAsBytes(body)),
            payloadHex.invoke(service, "aep_turbo", body));
    }

    @Test void cat1DoubleEncodedParamsUsePlatformTimestampAndBusinessAlarmCode() throws Exception {
        String encoded = json.writeValueAsString(json.writeValueAsString(java.util.Map.of(
            "temperature_value", 17, "smoke_concentration", 9, "battery", 75, "deviceStateCode", "1001001")));
        JsonNode body = json.readTree("{\"platformMsg\":{\"deviceId\":\"stable-cat1\",\"timestamp\":1793342705},\"params\":" + encoded + "}");
        var parsed = new FormTtlNormalizer(json).parse(body, "formttl", "Fault");
        assertEquals("stable-cat1", parsed.vendorDeviceId());
        assertEquals(OffsetDateTime.parse("2026-10-30T06:45:05Z"), parsed.observedAt());
        assertEquals("ALARM", parsed.kind());
        assertEquals("thing.event.alarms", parsed.events().getFirst().method());
        assertTrue(parsed.events().getFirst().paramsJson().contains("temperature_value"));
        assertTrue(parsed.events().getFirst().paramsJson().contains("smoke_concentration"));
    }

    @Test void unknownCat1BusinessCodeAndMissingTimeStayUnknown() throws Exception {
        var parsed = new FormTtlNormalizer(json).parse(json.readTree(
            "{\"deviceId\":\"cat1\",\"messageType\":\"other\",\"params\":\"{\\\"deviceStateCode\\\":\\\"9999999\\\",\\\"smoke_concentration\\\":209}\"}"), "formttl", null);
        assertEquals("OTHER", parsed.kind());
        assertNull(parsed.observedAt());
        assertFalse(parsed.events().getFirst().paramsJson().contains("alarm_time"));
    }

    @Test void cat1FormEnvelopeIsParsedWithoutReplacingStoredRawBody() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LegacyEventSink sink = mock(LegacyEventSink.class);
        LegacyDecodeService decoder = mock(LegacyDecodeService.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked") RowMapper<Object> row = (RowMapper<Object>) invocation.getArgument(1);
            if (sql.startsWith("SELECT id,state,failure_code")) return List.of();
            if (sql.startsWith("SELECT DISTINCT d.tenant_id")) {
                var rs = mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("tenant");
                when(rs.getString(2)).thenReturn("device");
                when(rs.getString(3)).thenReturn(null);
                return List.of(row.mapRow(rs, 0));
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var service = new LegacyIngestService(jdbc, json, sink, decoder,
            List.of(new FormTtlNormalizer(json)), mock(com.fireplatform.identity.CoordinationFence.class));
        String msg = "{\"platformMsg\":{\"deviceId\":\"cat1-id\",\"timestamp\":1793342705},\"params\":\"{\\\"temperature_value\\\":17,\\\"smoke_concentration\\\":9,\\\"deviceStateCode\\\":\\\"1001001\\\"}\"}";
        String raw = "msg=" + java.net.URLEncoder.encode(msg, StandardCharsets.UTF_8);

        var result = service.ingest("HTTP_FORM_TTL", "formttl", "Alarm", raw);

        assertEquals("NORMALIZED", result.state());
        ArgumentCaptor<Object[]> stored = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(startsWith("INSERT INTO legacy_ingest_messages"), stored.capture());
        assertEquals(raw, stored.getValue()[7]);
        ArgumentCaptor<LegacyNormalizedEvent> accepted = ArgumentCaptor.forClass(LegacyNormalizedEvent.class);
        verify(sink).accept(anyString(), accepted.capture());
        assertEquals("ALARM", accepted.getValue().events().getFirst().kind());
        assertEquals("2026-10-30T06:45:05Z", accepted.getValue().observedAt().toString());
    }

    @Test void telemetryProjectionKeepsGlobalAndCat1MetricsButOnlyDropsBatterySentinel255() throws Exception {
        var queries = new com.fireplatform.duty.DutyTelemetryQueries(null, null, null, null, json, null, null);
        Class<?> rawType = Class.forName("com.fireplatform.duty.DutyTelemetryQueries$Raw");
        var constructor = rawType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object raw = constructor.newInstance("event", "tenant", "device", "name", "address", null,
            "{\"temperature\":24.4,\"temperature_value\":17,\"smoke_concentration\":255,\"battery\":255,\"rsrp\":\"-108.0\",\"sinr\":\"13.0\",\"imei\":\"private\",\"iccid\":\"private\",\"unknown_metric\":7}",
            OffsetDateTime.parse("2026-10-30T06:45:05Z"), null, null, "formttl");
        var sample = com.fireplatform.duty.DutyTelemetryQueries.class.getDeclaredMethod("sample", rawType, java.util.Map.class, String.class);
        sample.setAccessible(true);
        var projected = (com.fireplatform.duty.DutyTelemetryQueries.Sample) sample.invoke(queries, raw, java.util.Map.of(), null);
        assertEquals(List.of("rsrp", "sinr", "smoke_concentration", "temperature", "temperature_value"),
            projected.readings().stream().map(com.fireplatform.duty.DutyTelemetryQueries.Reading::metric).toList());
        assertEquals("-108.0", projected.readings().get(0).value());
        assertEquals("13.0", projected.readings().get(1).value());
        var number = com.fireplatform.duty.DutyTelemetryQueries.class.getDeclaredMethod("metricNumber", String.class, JsonNode.class);
        number.setAccessible(true);
        assertNull(number.invoke(null, "sinr", json.readTree("\"1e9999999\"")));
        assertNull(number.invoke(null, "temperature", json.readTree("\"24.4\"")));
        assertEquals("255", projected.readings().get(2).value());
        assertEquals("24.4", projected.readings().get(3).value());
        assertTrue(projected.readings().stream().allMatch(reading -> reading.unitStatus().equals("UNKNOWN")));
    }

    @Test void serialAndFrameCounterKeysIncludeDeviceTimeAndPayloadIdentity() throws Exception {
        var service = new LegacyIngestService(null, json, null, null, List.of(), null);
        var external = LegacyIngestService.class.getDeclaredMethod("external", LegacyIngressNormalizer.Parsed.class, String.class);
        external.setAccessible(true);
        var a = new LegacyIngressNormalizer.Parsed("device-a", "17", "17", OffsetDateTime.parse("2026-10-30T06:45:05Z"), null, "OTHER", false, List.of());
        var b = new LegacyIngressNormalizer.Parsed("device-b", "17", "17", OffsetDateTime.parse("2026-10-30T06:45:05Z"), null, "OTHER", false, List.of());
        var frameA = new LegacyIngressNormalizer.Parsed("device-a", null, "17", OffsetDateTime.parse("2026-10-30T06:45:05Z"), null, "OTHER", false, List.of());
        var frameB = new LegacyIngressNormalizer.Parsed("device-b", null, "17", OffsetDateTime.parse("2026-10-30T06:45:05Z"), null, "OTHER", false, List.of());
        var frameLater = new LegacyIngressNormalizer.Parsed("device-a", null, "17", OffsetDateTime.parse("2026-10-30T06:45:06Z"), null, "OTHER", false, List.of());
        assertNotEquals(external.invoke(service, frameA, "digest-a"), external.invoke(service, frameB, "digest-a"));
        assertNotEquals(external.invoke(service, frameA, "digest-a"), external.invoke(service, frameLater, "digest-a"));
        assertEquals(external.invoke(service, frameA, "digest-a"), external.invoke(service, frameA, "digest-a"));
        var later = new LegacyIngressNormalizer.Parsed("device-a", "17", "17", OffsetDateTime.parse("2026-10-30T06:45:06Z"), null, "OTHER", false, List.of());
        assertNotEquals(external.invoke(service, a, "digest-a"), external.invoke(service, b, "digest-a"));
        assertNotEquals(external.invoke(service, a, "digest-a"), external.invoke(service, later, "digest-a"));
        assertEquals(external.invoke(service, a, "digest-a"), external.invoke(service, a, "digest-a"));
        var undated = new LegacyIngressNormalizer.Parsed("device-a", null, null, null, null, "OTHER", false, List.of());
        assertNotEquals(external.invoke(service, undated, "digest-a"), external.invoke(service, undated, "digest-b"));
        assertEquals(external.invoke(service, undated, "digest-a"), external.invoke(service, undated, "digest-a"));
        assertTrue(((String) external.invoke(service, a, "digest-a")).length() <= 160);
    }
}
