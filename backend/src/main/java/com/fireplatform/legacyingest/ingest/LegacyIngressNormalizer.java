package com.fireplatform.legacyingest.ingest;

import com.fireplatform.legacyingest.LegacyNormalizedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

public interface LegacyIngressNormalizer {
    record Parsed(String vendorDeviceId,String serial,String fcnt,OffsetDateTime observedAt,java.math.BigDecimal battery,String kind,boolean pendingDecoder,List<LegacyNormalizedEvent.LegacyEvent> events) { }
    Parsed parse(JsonNode body,String platform,String messageType);
}
