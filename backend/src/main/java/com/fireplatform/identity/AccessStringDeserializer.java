package com.fireplatform.identity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Access administration never coerces scalar JSON into text or repairs malformed UTF-16. */
public final class AccessStringDeserializer extends StdDeserializer<String> {
    public AccessStringDeserializer() { super(String.class); }

    @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING)
            throw JsonMappingException.from(parser, "授权文本字段必须是JSON字符串");
        String value = parser.getText();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || (Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t'))
                throw JsonMappingException.from(parser, "授权文本字段包含无效字符");
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c) || ++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw JsonMappingException.from(parser, "授权文本字段不是有效Unicode");
            }
        }
        return value;
    }
}
