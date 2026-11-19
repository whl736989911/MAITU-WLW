package com.fireplatform.legacyingest.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TurmassFrameNormalizerTest {
    private static final String HEARTBEAT = "7E026AE866EC010D001B64AA65080000000100000002140200000B01152401646902FFA56D010D";
    private final ObjectMapper mapper = new ObjectMapper();
    private final TurmassNormalizer normalizer = new TurmassNormalizer();

    private LegacyIngressNormalizer.Parsed parse(String terminal, String hex) throws Exception {
        return parse(terminal, hex, null);
    }

    private LegacyIngressNormalizer.Parsed parse(String terminal, String hex, String sourceTime) throws Exception {
        var body = mapper.createObjectNode().put("addr", terminal).put("data", hex);
        if (sourceTime != null) body.put("tmietmp", sourceTime);
        return normalizer.parse(body, "turmass", null);
    }

    @Test void decodes516Heartbeat() throws Exception {
        var parsed = parse("00000002", HEARTBEAT);
        assertThat(parsed.vendorDeviceId()).isEqualTo("00000002");
        assertThat(parsed.kind()).isEqualTo("HEARTBEAT");
        assertThat(parsed.fcnt()).isEqualTo("269");
        assertThat(parsed.observedAt().toString()).startsWith("2026-11-02T10:33:16");
        assertThat(parsed.battery().intValue()).isEqualTo(100);
        assertThat(parsed.events().getFirst().paramsJson())
                .contains("\"gateway\":\"00000001\"", "\"partsAddress\":\"00000002\"",
                        "\"temperature\":21", "\"battery\":100", "\"rssi\":-91", "\"snr\":13");
        assertThat(parsed.pendingDecoder()).isFalse();
    }

    @Test void decodes516TamperFault() throws Exception {
        var parsed = parse("00000004", "7E026ADFB8960187001E0A1365080000000300000004020103140200030B01172401646902FFB66D0111");
        assertThat(parsed.kind()).isEqualTo("FAULT");
        assertThat(parsed.events().getFirst().method()).isEqualTo("thing.event.faults");
        assertThat(parsed.events().getFirst().paramsJson()).contains("1002003", "防拆报警");
    }

    @Test void unknownFaultCodeIsNotReportedAsHeartbeatAndRemainsUnclassified() throws Exception {
        var parsed = parse("00000004", "7E026ADFB8960187001EFA73650800000003000000040201FF140200030B01172401646902FFB66D0111");
        var event = parsed.events().getFirst();
        assertThat(event.kind()).isEqualTo("OTHER");
        assertThat(event.method()).isEqualTo("thing.event.other");
        assertThat(event.paramsJson()).contains("\"deviceStateCode\":\"FF\"", "未知故障类型");
    }

    @Test void invalidFramesDoNotProduceEventsOrHealthMetadata() throws Exception {
        String valid = HEARTBEAT;
        assertRejected(valid.substring(0, 20) + "0000" + valid.substring(24));
        assertRejected(valid.substring(0, 24) + valid.substring(26));
        assertRejected(frame((byte) 0x02, "010401"));
        assertRejected("FFFE" + valid.substring(4));
        assertRejected(valid + "00");
        assertRejected(frame((byte) 0x01, "0102FF"));
    }

    @Test void masksTypeToLowNibbleAndDoesNotStripTrailing7E() throws Exception {
        var marked = parse("00000002", HEARTBEAT.substring(0, 2) + "F2" + HEARTBEAT.substring(4));
        assertThat(marked.kind()).isEqualTo("HEARTBEAT");
        assertThat(marked.events()).hasSize(1);
        var trailingValue = parse("00000002", frame((byte) 0x02, "650800000001000000020B017E"));
        assertThat(trailingValue.kind()).isEqualTo("HEARTBEAT");
        assertThat(trailingValue.events().getFirst().paramsJson()).contains("\"temperature\":126");
    }
    @Test void parameterAndResponseTemperatureIsAlarmThresholdAndZeroEpochStaysUnknown() throws Exception {
        String identity = "650800000001000000020B0115";
        for (byte type : new byte[] {1, 5}) {
            var parsed = parse("00000002", frame(type, identity));
            assertThat(parsed.events().getFirst().paramsJson())
                    .contains("\"temperature_alarm_up\":21")
                    .doesNotContain("\"temperature\":");
        }
        String zeroEpochFrame = frame((byte) 2, "65080000000100000002", "00000000");
        var unknownTime = parse("00000002", zeroEpochFrame);
        assertThat(unknownTime.observedAt()).isNull();
        assertThat(unknownTime.events().getFirst().paramsJson()).doesNotContain("\"time\":");
        var envelopeTime = parse("00000002", zeroEpochFrame, "2026-11-16T12:00:00Z");
        assertThat(envelopeTime.observedAt().toString()).isEqualTo("2026-11-16T12:00Z");
    }


    private void assertRejected(String hex) throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parse("00000002", hex))
                .isInstanceOfSatisfying(com.fireplatform.shared.ApiError.class,
                        error -> assertThat(error.code).isEqualTo("TURMASS_FRAME_INVALID"))
                .hasMessageContaining("Turmass Java 7E帧无效");
    }

    private String frame(byte type, String payload) {
        return frame(type, payload, "6AE866EC");
    }

    private String frame(byte type, String payload, String epoch) {
        int crc = 0xFFFF;
        byte[] bytes = java.util.HexFormat.of().parseHex(payload);
        for (byte value : bytes) {
            crc ^= value & 0xFF;
            for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xA001);
        }
        return String.format("7E%02X%s010D%04X%02X%02X%s", type & 0xFF, epoch, bytes.length,
                crc & 0xFF, (crc >>> 8) & 0xFF, payload);
    }
}
