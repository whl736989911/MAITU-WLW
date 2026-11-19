package com.fireplatform.ingestion;

import com.fireplatform.shared.ApiError;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.HexFormat;

public final class IngestionLimits {
    private IngestionLimits() { }
    public static final int SOURCE_MESSAGE_ID=128, VENDOR_DEVICE_ID=256, REPORTED_AT_LENGTH=128, PAYLOAD_BYTES=65536, OCCURRED_AT_LENGTH=64;
    public static final Instant MIN_REPORTED=Instant.parse("0001-01-01T00:00:00Z"), MAX_REPORTED=Instant.parse("+10000-01-01T00:00:00Z");
    public static byte[] utf8(String value) {
        if(value==null) return null;
        try { ByteBuffer buffer=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)); byte[] bytes=new byte[buffer.remaining()]; buffer.get(bytes); return bytes; }
        catch(CharacterCodingException e) { throw ApiError.bad("文本编码无效"); }
    }
    public static String decode(byte[] bytes) { return bytes==null ? null : new String(bytes,StandardCharsets.UTF_8); }
    public static void text(String value,int maximum) { if(value==null || value.isBlank() || value.length()>maximum || value.indexOf(0)>=0) throw ApiError.bad("标识或元数据无效"); utf8(value); }
    public static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA256 unavailable"); } }
    public record Reported(OffsetDateTime value,String quality) { }
    public static Reported reported(String original) {
        if(original==null) return new Reported(null,"MISSING");
        try {
            var value=OffsetDateTime.parse(original); var instant=value.toInstant();
            if(instant.isBefore(MIN_REPORTED) || !instant.isBefore(MAX_REPORTED)) return new Reported(null,"INVALID");
            return new Reported(value.withOffsetSameInstant(ZoneOffset.UTC),"REPORTED");
        } catch(java.time.DateTimeException e) { return new Reported(null,"INVALID"); }
    }
}
