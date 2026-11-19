package com.fireplatform.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class ProductModels {
    private ProductModels() { }
    public record Product(String id,String name,String manufacturer,String model,String description,long revision,String state,OffsetDateTime archivedAt) { public Product { if(state==null) state="ACTIVE"; } }
    public record BatteryThreshold(String parameterKey,String metricKey,String unit,String comparator,BigDecimal threshold) { }
    /** One declared reading actually projected from a captured frame; the value keeps its exact JSON type. */
    public record MeasurementReading(String key,String label,String valueType,JsonNode value,String unit) { }
    public record TestRow(String name,boolean passed,String eventType,BigDecimal batteryPercent,List<MeasurementReading> readings,JsonNode rawEvent,List<String> diagnostics) { public TestRow { if(readings==null) readings=List.of(); } }
    public record TestResult(String versionId,long revision,boolean passed,List<TestRow> results) { }
    public record TestSummary(boolean passed,long testedRevision,String schemaFingerprint,List<ProductInputs.Sample> samples,List<TestRow> results) { }
    public record Version(String id,String productId,long versionNumber,String status,long revision,JsonNode schema,JsonNode capabilities,BatteryThreshold batteryLowThreshold,boolean parserTested,OffsetDateTime publishedAt,TestSummary parserTestSummary,OffsetDateTime createdAt) { }
}
