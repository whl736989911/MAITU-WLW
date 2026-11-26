package com.fireplatform.legacyingest.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.*;
import com.fireplatform.legacyingest.*;
import com.fireplatform.legacyingest.codec.LegacyDecodeService;
import java.nio.file.*;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;

class LegacyIngestVectorTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private JsonNode vector(String name) throws Exception {
        String configured = System.getenv("LEGACY_APPENDIX");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(), "未指定私有 LEGACY_APPENDIX");
        Path fixture = Path.of(configured).resolve("test-vectors").resolve(name + ".json");
        assertTrue(Files.isRegularFile(fixture), "LEGACY_APPENDIX 缺少向量: " + name);
        return mapper.readTree(fixture.toFile()).path("payload");
    }
    @Test void lorawanVectorIsPendingWithPreservedRadioMetadata()throws Exception{var parsed=new LorawanTurboNormalizer(mapper).parse(vector("lorawan_uplink"),"lorawan_turbo",null);assertTrue(parsed.pendingDecoder());assertEquals("OTHER",parsed.kind());assertEquals(1,parsed.events().size());assertEquals("2026-10-30T07:07:22Z",parsed.observedAt().toString());assertEquals("100",parsed.battery().toPlainString());assertTrue(parsed.events().getFirst().paramsJson().contains("B2B000130000E1"));assertTrue(parsed.events().getFirst().paramsJson().contains("rssi"));}
    @Test void aepVectorNormalizesPayloadFields() throws Exception {
        var body = mapper.readTree("""
            {"deviceId":"aep-business-device","IMEI":"000000000000001","messageType":"dataReport",
             "payload":{"battery_value":100}}""");
        var parsed = new AepTurboNormalizer(mapper).parse(body, "aep_turbo", null);
        assertTrue(parsed.pendingDecoder());
        assertEquals("aep-business-device", parsed.vendorDeviceId());
        assertEquals("OTHER", parsed.kind());
        assertEquals(1, parsed.events().size());
        assertNull(parsed.observedAt());
        assertEquals("100", parsed.battery().toPlainString());
        assertTrue(parsed.events().getFirst().paramsJson().contains("battery_value"));
    }
    @Test void turmassVectorDecodesHeartbeatWithTelemetry() throws Exception {
        var parsed = new TurmassNormalizer().parse(vector("turmass_uplink"), "turmass", null);
        assertFalse(parsed.pendingDecoder());
        assertEquals("HEARTBEAT", parsed.kind());
        assertEquals("2026-10-30T06:45:05Z", parsed.observedAt().toString());
        assertEquals("100", parsed.battery().toPlainString());
        var telemetry = mapper.readTree(parsed.events().getFirst().paramsJson());
        var sourceAddress = telemetry.has("partsAddress") ? telemetry.required("partsAddress") : telemetry.required("gateway");
        assertTrue(sourceAddress.asText().matches("[0-9]{8}"));
        assertEquals(sourceAddress.asText(), parsed.vendorDeviceId());
        assertEquals(7, telemetry.path("smoke_value").asInt());
        assertEquals(22, telemetry.path("temperature").asInt());
        assertEquals(-96, telemetry.path("rssi").asInt());
        assertEquals(13, telemetry.path("snr").asInt());
    }
    @Test void tyyVectorExtractsHexPayloadAndCompactUtcTime()throws Exception{String raw="{\"notifyType\":\"deviceDataChanged\",\"service\":{\"serviceType\":\"ServiceAPI\",\"data\":{\"payload\":\"7e016ae7ac2807fa004001006508\"},\"eventTime\":\"20261101T211632Z\",\"serviceId\":\"ServiceAPI\"},\"deviceId\":\"aep-device\"}";var parsed=new TyyTurboNormalizer(mapper).parse(mapper.readTree(raw),"tyy_turbo",null);assertTrue(parsed.pendingDecoder());assertEquals("2026-11-01T21:16:32Z",parsed.observedAt().toString());assertTrue(parsed.events().getFirst().paramsJson().contains("7e016ae7ac28"));}

    @Test void lorawanBatterySentinelDoesNotSupplyABatteryReading() throws Exception {
        var body = mapper.readTree("""
            {"deveui":"0000000000000001","battery":255,"fcnt":250,"rssi":-129,"datetime":"2026-11-01T20:28:12Z"}""");
        var normalizer = new LorawanTurboNormalizer(mapper);
        var parsed = normalizer.parse(body, "lorawan_turbo", null);
        assertNull(parsed.battery());
        var params = mapper.readTree(parsed.events().getFirst().paramsJson());
        assertFalse(params.hasNonNull("battery"));
        assertEquals(-129, params.required("rssi").intValue());
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("battery", 0);
        assertEquals(0, normalizer.parse(body, "lorawan_turbo", null).battery().intValueExact());
    }

    @Test void tyyUsesNestedServiceIdentityAndDataTimestampWhenEnvelopeOmitsThem() throws Exception {
        var body = mapper.readTree("""
            {"notifyType":"deviceDataChanged","service":{"serviceType":"ServiceAPI",
             "data":{"payload":"7e0100","eventTime":"20261101T211632Z"},
             "deviceId":"tyy-terminal","gatewayId":"tyy-gateway"}}""");
        var parsed = new TyyTurboNormalizer(mapper).parse(body, "tyy_turbo", null);
        assertEquals("tyy-terminal", parsed.vendorDeviceId());
        assertEquals("2026-11-01T21:16:32Z", parsed.observedAt().toString());
        assertTrue(parsed.pendingDecoder());
    }
    @Test void ttlVectorUsesHeartbeatAndUtcPlusEightTime()throws Exception{var parsed=new FormTtlNormalizer(mapper).parse(vector("ttl_to_tsf"),"formttl","telemetry");assertFalse(parsed.pendingDecoder());assertEquals("HEARTBEAT",parsed.kind());assertEquals(1,parsed.events().size());assertEquals("2026-10-30T06:44:16Z",parsed.observedAt().toString());assertEquals("100",parsed.battery().toPlainString());}
    @Test void formTtlFaultWithoutSourceTimeRemainsFaultWithUnknownObservationTime() throws Exception {
        var parsed = new FormTtlNormalizer(mapper).parse(
            mapper.readTree("{\"deviceId\":\"sample\",\"parseMsg\":[{\"method\":\"thing.event.faults\",\"params\":[{\"deviceStateCode\":\"X-17\"}]}]}"),
            "formttl", null);
        assertEquals("FAULT", parsed.events().getFirst().kind());
        assertNull(parsed.events().getFirst().observedAt());
        assertNull(parsed.observedAt());
    }

    @Test void cat1GenericAlarmsPathDoesNotTurnAnUntypedReportIntoAnAlarm() throws Exception {
        var parsed = new FormTtlNormalizer(mapper).parse(
            mapper.readTree("""
                {"deviceId":"sample","params":{"temperature_value":26,"battery":100}}"""),
            "formttl", "alarms");
        assertEquals("OTHER", parsed.events().getFirst().kind());
        assertEquals(26, mapper.readTree(parsed.events().getFirst().paramsJson()).required("temperature_value").intValue());
        assertNull(parsed.observedAt());
    }

    @Test void cat1LegacyHearBeatTypeTakesPrecedenceOverTheGenericAlarmsPath() throws Exception {
        var parsed = new FormTtlNormalizer(mapper).parse(
            mapper.readTree("""
                {"deviceId":"sample","messageType":"HearBeat","ts":"2026-11-20T17:53:00+08:00","params":{"battery":100}}"""),
            "formttl", "alarms");
        assertEquals("HEARTBEAT", parsed.events().getFirst().kind());
        assertEquals("2026-11-20T09:53:00Z", parsed.observedAt().toInstant().toString());
    }

    private record Fixture(LegacyIngestService service,JdbcTemplate jdbc,LegacyDecodeService decoder,LegacyEventSink sink) { }
    private Fixture fixture(String legacyProductId,boolean decode)throws Exception{
        JdbcTemplate jdbc=mock(JdbcTemplate.class);LegacyDecodeService decoder=mock(LegacyDecodeService.class);LegacyEventSink sink=mock(LegacyEventSink.class);
        doAnswer(invocation->{String sql=invocation.getArgument(0);if(!sql.startsWith("SELECT DISTINCT d.tenant_id"))return List.of();@SuppressWarnings("unchecked") RowMapper<Object> row=(RowMapper<Object>)invocation.getArgument(1);ResultSet rs=mock(ResultSet.class);when(rs.getString(1)).thenReturn("tenant");when(rs.getString(2)).thenReturn("device");when(rs.getString(3)).thenReturn(legacyProductId);return List.of(row.mapRow(rs,0));}).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        when(jdbc.queryForObject(startsWith("SELECT EXISTS"),eq(Boolean.class),any(Object[].class))).thenReturn(true);when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        if(decode)when(decoder.decode(any(LegacyDecodeService.DeviceBinding.class),anyString(),anyString())).thenAnswer(call -> new LegacyNormalizedEvent("tenant","device",call.<String>getArgument(1),"lorawan_turbo","HEARTBEAT",null,null,false,List.of(new LegacyNormalizedEvent.LegacyEvent("HEARTBEAT","thing.event.heartbeat","{}",null))));
        var service=new LegacyIngestService(jdbc,mapper,sink,decoder,List.of(new LorawanTurboNormalizer(mapper),new AepTurboNormalizer(mapper),new TyyTurboNormalizer(mapper),new TurmassNormalizer(),new MaituMqttNormalizer(mapper),new FormTtlNormalizer(mapper)),mock(com.fireplatform.identity.CoordinationFence.class));return new Fixture(service,jdbc,decoder,sink);
    }
    @Test void boundDecoderNormalizesLorawanVector() throws Exception {
        var body = vector("lorawan_uplink");
        var f = fixture("334", true);
        var result = f.service().ingest("HTTP_NOTIFY", "lorawan_turbo", null, mapper.writeValueAsString(body));
        assertEquals("NORMALIZED", result.state());
        assertTrue(result.events() >= 1);
        verify(f.decoder()).decode(argThat(binding -> binding.legacyProductId().equals("334")),
            eq(body.required("deveui").asText()), eq("B2B000130000E1"));
        verify(f.jdbc()).update(startsWith("UPDATE legacy_ingest_messages SET state=?"), eq("NORMALIZED"), anyString());
    }
    @Test void unboundProductLeavesLorawanPending()throws Exception{var f=fixture(null,false);var result=f.service().ingest("HTTP_NOTIFY","lorawan_turbo",null,mapper.writeValueAsString(vector("lorawan_uplink")));assertEquals("PENDING_DECODER",result.state());assertTrue(result.events()>=1);ArgumentCaptor<LegacyNormalizedEvent> normalized=ArgumentCaptor.forClass(LegacyNormalizedEvent.class);verify(f.sink()).accept(anyString(),normalized.capture());assertEquals("OTHER",normalized.getValue().events().getFirst().kind());verify(f.decoder(),never()).decode(any(),anyString(),anyString());}
    @Test void decoderFailureKeepsRawMessageAndPendingState()throws Exception{var f=fixture("missing-product",false);when(f.decoder().decode(any(LegacyDecodeService.DeviceBinding.class),anyString(),anyString())).thenThrow(new IllegalStateException("decoder failed"));String raw=mapper.writeValueAsString(vector("lorawan_uplink"));var result=f.service().ingest("HTTP_NOTIFY","lorawan_turbo",null,raw);assertEquals("PENDING_DECODER",result.state());assertTrue(result.events()>=1);ArgumentCaptor<Object[]> values=ArgumentCaptor.forClass(Object[].class);verify(f.jdbc()).update(startsWith("INSERT INTO legacy_ingest_messages"),values.capture());assertEquals(raw,values.getValue()[7]);}
    @Test void maituMqttPayloadIsStoredPendingWithoutDecoderBinding()throws Exception{var f=fixture(null,false);String raw="{\"deviceId\":\"0000000000000001\",\"data\":\"B2B000130000E1\",\"ts\":1793342705}";var result=f.service().ingest("MQTT_IOT_UPLINK","maitu_mqtt",null,raw);assertEquals("PENDING_DECODER",result.state());ArgumentCaptor<Object[]> values=ArgumentCaptor.forClass(Object[].class);verify(f.jdbc()).update(startsWith("INSERT INTO legacy_ingest_messages"),values.capture());assertEquals(raw,values.getValue()[7]);}
}
