package com.fireplatform.ingestion;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Capture frames are strings, never Jackson's default number/boolean-to-text coercion. */
public class StrictStringDeserializer extends StdDeserializer<String> {
    public StrictStringDeserializer() { super(String.class); }
    @Override public String deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(parser.currentToken()!=JsonToken.VALUE_STRING) return (String)context.handleUnexpectedToken(String.class,parser);
        return parser.getText();
    }
}
