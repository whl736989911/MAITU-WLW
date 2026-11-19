package com.fireplatform.shared;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigDecimal;

/** Decimal configuration accepts numeric JSON tokens, never coerced strings or booleans. */
public final class DecimalTokenDeserializer extends JsonDeserializer<BigDecimal> {
    @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT && parser.currentToken() != JsonToken.VALUE_NUMBER_FLOAT)
            return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
        return parser.getDecimalValue();
    }
}
