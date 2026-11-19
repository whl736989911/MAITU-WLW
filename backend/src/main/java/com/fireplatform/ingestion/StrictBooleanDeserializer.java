package com.fireplatform.ingestion;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public class StrictBooleanDeserializer extends StdDeserializer<Boolean> {
    public StrictBooleanDeserializer() { super(Boolean.class); }
    @Override public Boolean deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(parser.currentToken()!=JsonToken.VALUE_TRUE && parser.currentToken()!=JsonToken.VALUE_FALSE) return (Boolean)context.handleUnexpectedToken(Boolean.class,parser);
        return parser.getBooleanValue();
    }
}
