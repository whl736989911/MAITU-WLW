package com.fireplatform.legacyingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Stable public fault-code projection over the normalized legacy event payload. */
public final class FaultProjection {
    public record Description(String code, String label) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] CODE_FIELDS = { "deviceStateCode", "code", "eventCode" };

    private FaultProjection() { }


    public static Description describe(String method, String paramsJson) {
        if (paramsJson != null && !paramsJson.isBlank()) {
            try { return describe(JSON.readTree(paramsJson)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { }
        }
        return describe(null);
    }

    public static Description describe(JsonNode params) {
        String code = null;
        if (params != null && params.isObject()) {
            for (String key : CODE_FIELDS) {
                JsonNode value = params.get(key);
                if (value != null && !value.isNull() && value.isValueNode()) {
                    String candidate = value.asText().trim();
                    if (!candidate.isEmpty()) { code = candidate; break; }
                }
            }
            if ("HIKVISION_FIRECLOUD".equals(params.path("source").asText())) {
                String label = params.path("fault_label").asText(null);
                if (label != null && !label.isBlank()) return new Description(code, label);
            }
        }
        return new Description(code, LegacyFaultCodes.label(code));
    }
}
