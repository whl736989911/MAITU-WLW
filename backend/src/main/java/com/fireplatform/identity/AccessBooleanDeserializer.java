package com.fireplatform.identity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public final class AccessBooleanDeserializer extends StdDeserializer<Boolean> {
    public AccessBooleanDeserializer() { super(Boolean.class); }
    @Override public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_TRUE && parser.currentToken() != JsonToken.VALUE_FALSE)
            throw JsonMappingException.from(parser, "启用状态必须是JSON布尔值");
        return parser.getBooleanValue();
    }
}
