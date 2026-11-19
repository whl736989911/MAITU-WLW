package com.fireplatform.identity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Revisions are canonical unsigned JSON integers exactly representable by the browser. */
public final class AccessRevisionDeserializer extends StdDeserializer<Long> {
    public AccessRevisionDeserializer() { super(Long.class); }

    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            throw JsonMappingException.from(parser, "版本必须是规范JSON整数");
        String raw = parser.getText();
        if (raw.isEmpty() || raw.charAt(0) == '-' || (raw.length() > 1 && raw.charAt(0) == '0'))
            throw JsonMappingException.from(parser, "版本必须是规范非负整数");
        long value = parser.getLongValue();
        if (value > AccessInputs.MAX_REVISION)
            throw JsonMappingException.from(parser, "版本超过安全整数范围");
        return value;
    }
}
