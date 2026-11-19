package com.fireplatform.ruleflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Strict request bodies: a wrong JSON type is rejected instead of being silently coerced. */
public final class RuleFlowInputs {
    private RuleFlowInputs() { }

    public static class IntegerOnly extends StdDeserializer<Long> {
        public IntegerOnly() { super(Long.class); }
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) return (Long) context.handleUnexpectedToken(Long.class, parser);
            return parser.getLongValue();
        }
    }

    public static class TextOnly extends StdDeserializer<String> {
        public TextOnly() { super(String.class); }
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) return (String) context.handleUnexpectedToken(String.class, parser);
            return parser.getText();
        }
    }

    public static class BooleanOnly extends StdDeserializer<Boolean> {
        public BooleanOnly() { super(Boolean.class); }
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_TRUE && parser.currentToken() != JsonToken.VALUE_FALSE)
                return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
            return parser.getBooleanValue();
        }
    }

    public record Create(@JsonDeserialize(using = TextOnly.class) String name,
                         @JsonDeserialize(using = TextOnly.class) String description,
                         Map<String, Object> definition,
                         @JsonDeserialize(using = TextOnly.class) String reason) { }

    public record Save(@JsonDeserialize(using = IntegerOnly.class) Long expected_revision,
                       @JsonDeserialize(using = TextOnly.class) String name,
                       @JsonDeserialize(using = TextOnly.class) String description,
                       Map<String, Object> definition,
                       @JsonDeserialize(using = TextOnly.class) String reason) { }

    public record Archive(@JsonDeserialize(using = IntegerOnly.class) Long expected_revision,
                          @JsonDeserialize(using = TextOnly.class) String reason) { }

    public record Preview(@JsonDeserialize(using = IntegerOnly.class) Long expected_revision) { }

    public record Publish(@JsonDeserialize(using = IntegerOnly.class) Long expected_revision,
                          @JsonDeserialize(using = TextOnly.class) String preview_hash,
                          @JsonDeserialize(using = TextOnly.class) String reason) { }

    public record Toggle(@JsonDeserialize(using = IntegerOnly.class) Long expected_revision,
                         @JsonDeserialize(using = BooleanOnly.class) Boolean enabled,
                         @JsonDeserialize(using = TextOnly.class) String reason) { }

    /** A compile outcome is reported inline so per-node errors reach the editor without a second request. */
    public record PublishResponse(boolean published, RuleFlowModels.Flow flow, Long revision, RuleFlowModels.Plan plan,
                                  List<RuleFlowModels.Binding> bindings, List<RuleFlowModels.Issue> issues) { }

    public record PreviewResponse(RuleFlowModels.Plan plan, List<RuleFlowModels.Binding> bindings) { }
}
