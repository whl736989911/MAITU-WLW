package com.fireplatform.legacyingest.codec;

import java.util.List;

public record CodecResult(boolean ok, List<DecodedEvent> events, String errorCode, String errorMessage) {
    public CodecResult { events = events == null ? List.of() : List.copyOf(events); }

    public static CodecResult success(List<DecodedEvent> events) { return new CodecResult(true, events, null, null); }
    public static CodecResult failure(String code) { return new CodecResult(false, List.of(), code, code); }

    public record DecodedEvent(String method, Object params) { }
}
