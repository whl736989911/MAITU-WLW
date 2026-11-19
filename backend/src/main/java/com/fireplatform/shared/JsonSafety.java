package com.fireplatform.shared;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonSafety {
    @Bean Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder->builder.postConfigurer(mapper->{
            mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
            mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(Limits.JSON_MAX_DEPTH).maxStringLength(Limits.JSON_MAX_STRING).maxNumberLength(Limits.JSON_MAX_NUMBER_LENGTH).build());
        });
    }
}
