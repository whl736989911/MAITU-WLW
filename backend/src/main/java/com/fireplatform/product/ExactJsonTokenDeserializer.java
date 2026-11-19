package com.fireplatform.product;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;

/** Test expectations keep the exact JSON token type and the exact decimal lexeme; no string coercion. */
public final class ExactJsonTokenDeserializer extends JsonDeserializer<JsonNode> {
    @Override public JsonNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        var token=parser.currentToken();
        if(token==JsonToken.VALUE_NUMBER_INT || token==JsonToken.VALUE_NUMBER_FLOAT) return DecimalNode.valueOf(parser.getDecimalValue());
        if(token==JsonToken.VALUE_STRING) return TextNode.valueOf(parser.getText());
        if(token==JsonToken.VALUE_TRUE) return BooleanNode.TRUE;
        if(token==JsonToken.VALUE_FALSE) return BooleanNode.FALSE;
        if(token==JsonToken.VALUE_NULL) return NullNode.instance;
        return (JsonNode)context.handleUnexpectedToken(JsonNode.class,parser);
    }
}
