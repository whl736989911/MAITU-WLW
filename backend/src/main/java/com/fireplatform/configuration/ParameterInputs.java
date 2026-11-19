package com.fireplatform.configuration;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.constraints.*;
import java.io.IOException;

public final class ParameterInputs {
    private ParameterInputs() { }
    public static class IntegerToken extends StdDeserializer<Long> {
        public IntegerToken() { super(Long.class); }
        @Override public Long deserialize(JsonParser p,DeserializationContext c) throws IOException { if(p.currentToken()!=JsonToken.VALUE_NUMBER_INT) return (Long)c.handleUnexpectedToken(Long.class,p); return p.getLongValue(); }
    }
    public static class StringToken extends StdDeserializer<String> {
        public StringToken() { super(String.class); }
        @Override public String deserialize(JsonParser p,DeserializationContext c) throws IOException { if(p.currentToken()!=JsonToken.VALUE_STRING) return (String)c.handleUnexpectedToken(String.class,p); return p.getText(); }
    }
    public record SetValue(@JsonDeserialize(using=IntegerToken.class) @NotNull Long value,@JsonDeserialize(using=IntegerToken.class) @NotNull @Min(0) Long expectedRevision,@JsonDeserialize(using=StringToken.class) @NotBlank @Size(max=com.fireplatform.shared.Limits.MAX_CHANGE_REASON_LENGTH) String reason) { }
    public record Clear(@JsonDeserialize(using=IntegerToken.class) @NotNull @Min(0) Long expectedRevision,@JsonDeserialize(using=StringToken.class) @NotBlank @Size(max=com.fireplatform.shared.Limits.MAX_CHANGE_REASON_LENGTH) String reason) { }
}
