package com.fireplatform.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/** Fixed P3 algorithm only. Capture/preview never pretends to be a synthetic publication test or applies business state. */
@Service
public class PublishedMessageNormalizer {
    public record Normalized(String eventType,BigDecimal batteryPercent,List<ProductModels.MeasurementReading> readings,String sourceEventValue,JsonNode rawEvent,List<String> diagnostics) { }
    private final ProductQueries queries; private final JsonPointerParser parser;
    public PublishedMessageNormalizer(ProductQueries queries,JsonPointerParser parser) { this.queries=queries; this.parser=parser; }
    public void validatePayload(String payload) { parser.validatePayload(payload); }
    public Normalized normalize(String publishedVersionId,String payload) {
        queries.requirePublished(publishedVersionId); var version=queries.version(publishedVersionId,false);
        var outcome=parser.parse(parser.validate(version.schema(),version.capabilities()),payload);
        return new Normalized(outcome.eventType(),outcome.batteryPercent(),outcome.readings(),outcome.sourceEventValue(),outcome.rawEvent(),outcome.diagnostics());
    }
}
