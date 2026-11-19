package com.fireplatform.legacyingest.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Parses the supported Java 7E gateway frame: header, payload length, payload-only
 * CRC-16/MODBUS and TLVs. This does not parse TBS110/LoRaWAN or other variants.
 */
@Component
public class TurmassNormalizer implements LegacyIngressNormalizer {
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();
    private static final Map<String, String> TERMINAL_FAULTS = Map.of(
            "02", "1002070", "03", "1002003", "14", "1002001", "15", "1002072");
    private static final Map<String, String> GATEWAY_FAULTS = Map.of(
            "16", "1002036", "17", "1002077", "18", "1002078", "19", "1002078",
            "20", "1002079", "21", "1002080", "22", "1002035");

    @Override
    public Parsed parse(JsonNode body, String platform, String messageType) {
        String addr = NormalizerSupport.text(body, "addr");
        String frame = NormalizerSupport.text(body, "data");
        OffsetDateTime fallbackTime = NormalizerSupport.time(NormalizerSupport.text(body, "tmietmp"));
        try {
            return decode(addr, frame, fallbackTime);
        } catch (RuntimeException error) {
            throw new com.fireplatform.shared.ApiError(400, "TURMASS_FRAME_INVALID",
                    "Turmass Java 7E帧无效（长度、CRC或TLV不匹配）");
        }
    }

    private Parsed decode(String fallbackAddress, String hex, OffsetDateTime fallbackTime) {
        byte[] bytes = hex(hex);
        if (bytes.length < 12 || (bytes[0] & 0xFF) != 0x7E) throw new IllegalArgumentException("invalid Java frame");
        int typeValue = bytes[1] & 0x0F;
        long epoch = ((bytes[2] & 0xFFL) << 24) | ((bytes[3] & 0xFFL) << 16) | ((bytes[4] & 0xFFL) << 8) | (bytes[5] & 0xFFL);
        int fcnt = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        int length = ((bytes[8] & 0xFF) << 8) | (bytes[9] & 0xFF);
        if (bytes.length != 12 + length) throw new IllegalArgumentException("frame length mismatch");
        int expectedCrc = crc16Modbus(bytes, 12, length);
        int wireCrc = (bytes[10] & 0xFF) | ((bytes[11] & 0xFF) << 8);
        if (wireCrc != expectedCrc) throw new IllegalArgumentException("frame CRC mismatch");
        int end = bytes.length;

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fcnt", fcnt);
        String gateway = null, terminal = null;
        String alarmCode = null, alarmText = null;
        String method = null;
        String kind = "OTHER";
        boolean unknownFault = false;
        java.math.BigDecimal battery = null;

        for (int index = 12; index < end; ) {
            if (index + 2 > end) throw new IllegalArgumentException("truncated TLV header");
            int tag = bytes[index++] & 0xFF;
            int len = bytes[index++] & 0xFF;
            if (index + len > end) throw new IllegalArgumentException("truncated TLV value");
            int valueOffset = index;
            index += len;
            switch (tag) {
                case 0x65 -> {
                    if (len >= 8) {
                        gateway = bcd(bytes, valueOffset, 4);
                        terminal = bcd(bytes, valueOffset + 4, 4);
                        params.put("gateway", gateway);
                        if (!"00000000".equals(terminal)) params.put("partsAddress", terminal);
                    }
                }
                case 0x18 -> params.put("status", hex(bytes, valueOffset, len));
                case 0x05 -> {
                    if (len >= 2) {
                        params.put("hardware_version", "V" + (bytes[valueOffset] & 0xFF) / 10.0);
                        params.put("software_version", "V" + (bytes[valueOffset + 1] & 0xFF) / 10.0);
                    }
                }
                case 0x03 -> params.put("device_type", hex(bytes, valueOffset, len));
                case 0x06 -> params.put("heartbeat", unsigned(bytes, valueOffset, len));
                case 0x08 -> params.put("emergency_time", unsigned(bytes, valueOffset, len));
                case 0x0B -> params.put(typeValue == 1 || typeValue == 5 ? "temperature_alarm_up" : "temperature",
                        signed(bytes, valueOffset, len));
                case 0x21 -> params.put("temperature_alarm_down", signed(bytes, valueOffset, len));
                case 0x14 -> params.put("smoke_value", unsigned(bytes, valueOffset, len));
                case 0x16 -> {
                    if (len >= 12) {
                        params.put("all_frame", unsigned(bytes, valueOffset, 4));
                        params.put("alarm_count", unsigned(bytes, valueOffset + 4, 4));
                        params.put("heart_count", unsigned(bytes, valueOffset + 8, 4));
                    }
                }
                case 0x17 -> params.put("failed_frame_count", unsigned(bytes, valueOffset, len));
                case 0x19 -> { params.put("operation", "自检"); params.put("deviceState", "设备自检"); }
                case 0x20 -> params.put("buzzerSilenceTime", unsigned(bytes, valueOffset, len));
                case 0x24 -> {
                    long level = unsigned(bytes, valueOffset, len);
                    params.put("battery", level);
                    battery = java.math.BigDecimal.valueOf(level);
                }
                case 0x29 -> params.put("battery_voltage", unsigned(bytes, valueOffset, len) / 1000.0);
                case 0x69 -> params.put("rssi", signed(bytes, valueOffset, len));
                case 0x6D -> params.put("snr", signed(bytes, valueOffset, len));
                case 0x0E -> params.put("supply", hex(bytes, valueOffset, len));
                case 0x50 -> params.put("network", hex(bytes, valueOffset, len));
                case 0x66 -> params.put("IMSI", hex(bytes, valueOffset, len));
                case 0x70 -> params.put("ICCID", hex(bytes, valueOffset, len));
                case 0x6C -> params.put("rsrp", signed(bytes, valueOffset, len));
                case 0x4D -> params.put("rs485", hex(bytes, valueOffset, len));
                case 0x4E -> params.put("di", hex(bytes, valueOffset, len));
                case 0x4F -> params.put("do", hex(bytes, valueOffset, len));
                case 0xEA -> params.put("subnodes", hex(bytes, valueOffset, len));
                case 0x01 -> {
                    alarmCode = "1001001"; alarmText = "火灾报警"; kind = "ALARM"; method = "thing.event.alarms";
                }
                case 0x02 -> {
                    String code = hex(bytes, valueOffset, len);
                    boolean gatewayFrame = "00000000".equals(terminal);
                    String mapped = (gatewayFrame ? GATEWAY_FAULTS : TERMINAL_FAULTS).get(code);
                    alarmCode = mapped == null ? code : mapped;
                    alarmText = mapped == null ? "未知故障类型" : com.fireplatform.legacyingest.LegacyFaultCodes.label(mapped);
                    if (mapped != null) { kind = "FAULT"; method = "thing.event.faults"; }
                    else unknownFault = true;
                }
                case 0x4B -> { if (kind.equals("OTHER")) { method = "thing.event.other"; params.put("matching_codes", hex(bytes, valueOffset, len)); } }
                default -> { }
            }
        }
        String deviceId = terminal != null && !"00000000".equals(terminal) ? terminal : gateway != null ? gateway : fallbackAddress;
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("no address");
        if (unknownFault) {
            kind = "OTHER";
            method = "thing.event.other";
        } else if (method == null) {
            if (typeValue == 1 || typeValue == 3 || typeValue == 4) { method = "thing.event.params"; kind = "PARAM"; params.put("deviceState", "参数上报"); }
            else if (typeValue == 2) { method = "thing.event.heartbeat"; kind = "HEARTBEAT"; params.put("deviceState", "心跳上报"); }
            else { method = "thing.event.other"; }
        }
        if (alarmCode != null) { params.put("deviceStateCode", alarmCode); params.put("deviceState", alarmText); }
        OffsetDateTime observed = epoch == 0 ? fallbackTime : Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC);
        if (observed != null) params.put("time", observed.toString());
        String serial = deviceId + "|" + epoch + "|" + fcnt;
        var event = new com.fireplatform.legacyingest.LegacyNormalizedEvent.LegacyEvent(kind, method,
                json(params), observed);
        return new Parsed(deviceId, serial, String.valueOf(fcnt), observed, battery, kind, false, List.of(event));
    }

    private static String json(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }


    private static byte[] hex(String value) {
        int start = 0, end = value.length();
        while (start < end && value.charAt(start) <= ' ') start++;
        while (end > start && value.charAt(end - 1) <= ' ') end--;
        int digits = 0;
        for (int i = start; i < end; i++) if (value.charAt(i) != ' ') digits++;
        if ((digits & 1) != 0) throw new IllegalArgumentException("odd hex");
        byte[] out = new byte[digits / 2];
        int high = -1, target = 0;
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (c == ' ') continue;
            int digit = Character.digit(c, 16);
            if (digit < 0) throw new IllegalArgumentException("invalid hex");
            if (high < 0) high = digit;
            else { out[target++] = (byte) ((high << 4) | digit); high = -1; }
        }
        return out;
    }

    private static String hex(byte[] value, int offset, int length) {
        char[] chars = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int b = value[offset + i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[b >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(chars);
    }

    private static String bcd(byte[] value, int offset, int length) {
        return hex(value, offset, length);
    }

    private static long unsigned(byte[] value, int offset, int length) {
        long result = 0;
        for (int i = offset; i < offset + length; i++) result = (result << 8) | (value[i] & 0xFF);
        return result;
    }

    private static int signed(byte[] value, int offset, int length) {
        if (length >= 2) return (short) (((value[offset] & 0xFF) << 8) | (value[offset + 1] & 0xFF));
        return length == 1 ? value[offset] : 0;
    }

    private static int crc16Modbus(byte[] bytes, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= bytes[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xA001);
        }
        return crc & 0xFFFF;
    }
}
