package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fireplatform.product.*;
import com.fireplatform.shared.ApiError;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JsonPointerParserTest {
    final JsonPointerParser parser=new JsonPointerParser();
    final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    JsonNode read(String text) throws Exception { return json.readTree(text); }
    ObjectNode schema() { return (ObjectNode)parser.defaultSchema(); }
    ObjectNode caps() { return json.createObjectNode().put("fire","SUPPORTED").put("recovery","SUPPORTED").put("battery","SUPPORTED"); }
    JsonPointerParser.Configuration config() { return parser.validate(schema(),caps()); }
    ProductInputs.Sample sample(String name,String kind,String payload,String expected,String battery) { return new ProductInputs.Sample(name,kind,payload,expected,battery==null ? null : new BigDecimal(battery),List.of()); }
    List<ProductInputs.Sample> full() { return List.of(sample("fire","FIRE","{\"event\":\"FIRE\",\"battery\":0}","FIRE","0"),sample("recovery","RECOVERY","{\"event\":\"RECOVERY\",\"battery\":50}","RECOVERY","50"),sample("unknown","UNKNOWN","{\"event\":\"not-mapped\"}","UNKNOWN",null),sample("malformed","MALFORMED","{","UNKNOWN",null)); }
    @Test void missingNullAndUnknownEventsAreNeverNormalOrRecoveryAndZeroIsNotNull() {
        for(String payload:List.of("{}","{\"event\":null}","{\"event\":\"other\"}","{\"event\":{}}")) assertThat(parser.parse(config(),payload).eventType()).isEqualTo("UNKNOWN");
        assertThat(parser.parse(config(),"{\"event\":\"FIRE\",\"battery\":0}").batteryPercent()).isEqualByComparingTo("0");
        for(String payload:List.of("{\"event\":\"FIRE\"}","{\"event\":\"FIRE\",\"battery\":null}","{\"event\":\"FIRE\",\"battery\":\"0\"}")) assertThat(parser.parse(config(),payload).batteryPercent()).isNull();
    }
    @Test void rawNumberStringAndBooleanAreTypeExactWithoutFloatingPointCoercion() throws Exception {
        var schema=schema(); schema.set("event_mappings",read("[{\"raw_value\":1,\"event_type\":\"FIRE\"},{\"raw_value\":\"1\",\"event_type\":\"RECOVERY\"},{\"raw_value\":false,\"event_type\":\"RECOVERY\"}]")); var config=parser.validate(schema,caps());
        var number=parser.parse(config,"{\"event\":1.0,\"battery\":0}"); assertThat(number.eventType()).isEqualTo("FIRE"); assertThat(number.rawEvent().isNumber()).isTrue();
        var string=parser.parse(config,"{\"event\":\"1\"}"); assertThat(string.eventType()).isEqualTo("RECOVERY"); assertThat(string.rawEvent().isTextual()).isTrue();
        assertThat(parser.parse(config,"{\"event\":false}").eventType()).isEqualTo("RECOVERY"); assertThat(parser.parse(config,"{\"event\":true}").eventType()).isEqualTo("UNKNOWN");
        schema.set("event_mappings",read("[{\"raw_value\":1,\"event_type\":\"FIRE\"},{\"raw_value\":1.0,\"event_type\":\"RECOVERY\"}]")); assertThatThrownBy(()->parser.validate(schema,caps())).isInstanceOf(ApiError.class);
    }
    @Test void decimalTransformationIsExactAndNeverClampsRoundsOrInventsBattery() {
        var schema=schema(); ((ObjectNode)schema.get("battery")).put("scale",new BigDecimal("0.1")); var config=parser.validate(schema,caps());
        assertThat(parser.parse(config,"{\"event\":\"FIRE\",\"battery\":1.0000000000000001}").batteryPercent()).isEqualByComparingTo("0.10000000000000001");
        assertThat(parser.parse(config,"{\"battery\":1000}").batteryPercent()).isEqualByComparingTo("100");
        for(String value:List.of("-1","1001","\"1\"","null","1e1000000","1e-1000000","0.1000000000000000001")) assertThat(parser.parse(config,"{\"event\":\"FIRE\",\"battery\":"+value+"}").batteryPercent()).isNull();
    }
    @Test void invalidPointersUnknownFieldsMappingTypesAndNumericResourceAbuseAreRejected() throws Exception {
        for(String pointer:List.of("$.event","/a/*","/bad~2","/a/../event","/x".repeat(17),"/"+"x".repeat(256))) {
            var schema=schema(); schema.put("event_pointer",pointer); assertThatThrownBy(()->parser.validate(schema,caps())).isInstanceOf(ApiError.class);
        }
        var script=schema(); script.put("script","fetch('https://invalid.example/')"); assertThatThrownBy(()->parser.validate(script,caps())).isInstanceOf(ApiError.class);
        for(String bad:List.of("0","-1","1e1000000","1e-1000000","1000000000001","0.1000000000000000001")) {
            var schema=schema(); ((ObjectNode)schema.get("battery")).set("scale",read(bad)); assertThatThrownBy(()->parser.validate(schema,caps())).isInstanceOf(ApiError.class);
        }
        for(String bad:List.of("[{\"raw_value\":null,\"event_type\":\"FIRE\"},{\"raw_value\":\"r\",\"event_type\":\"RECOVERY\"}]","[{\"raw_value\":\"F\",\"event_type\":\"FIRE\"},{\"raw_value\":\"r\",\"event_type\":\"UNKNOWN\"}]","[{\"raw_value\":1e1000000,\"event_type\":\"FIRE\"},{\"raw_value\":2,\"event_type\":\"RECOVERY\"}]")) {
            var schema=schema(); schema.set("event_mappings",read(bad)); assertThatThrownBy(()->parser.validate(schema,caps())).isInstanceOf(ApiError.class);
        }
        var negative=schema(); ((ObjectNode)negative.get("battery")).put("min",-1); assertThatThrownBy(()->parser.validate(negative,caps())).isInstanceOf(ApiError.class);
        var maximum=schema(); ((ObjectNode)maximum.get("battery")).put("max",101); assertThatThrownBy(()->parser.validate(maximum,caps())).isInstanceOf(ApiError.class);
    }
    @Test void malformedDuplicateKeysDeepJsonAndHugeNumberStayUnknownWithSafeDiagnostics() {
        for(String payload:List.of("{","{\"event\":\"FIRE\",\"event\":\"RECOVERY\"}","[".repeat(34)+"0"+"]".repeat(34),"{\"event\":"+"1".repeat(65)+"}","{} {}")) {
            var result=parser.parse(config(),payload); assertThat(result.eventType()).isEqualTo("UNKNOWN"); assertThat(result.batteryPercent()).isNull(); assertThat(result.diagnostics()).containsExactly("PARSE_ERROR"); assertThat(result.rawEvent().isNull()).isTrue();
        }
    }
    @Test void coverageRequiresFourActualKindsNotForgedLabelsAndCapabilities() {
        assertThat(parser.evaluate(config(),full()).passed()).isTrue();
        var one=parser.evaluate(config(),List.of(full().getFirst())); assertThat(one.passed()).isFalse(); assertThat(one.results().getFirst().passed()).isTrue();
        var forged=new ArrayList<>(full()); forged.set(0,sample("fake-fire","FIRE","{}","UNKNOWN",null)); assertThat(parser.evaluate(config(),forged).passed()).isFalse();
        var pending=parser.pendingCapabilities(); assertThat(parser.evaluate(parser.validate(schema(),pending),full()).passed()).isFalse();
        var noMissing=new ArrayList<>(full()); noMissing.set(2,sample("unknown","UNKNOWN","{\"event\":\"other\",\"battery\":50}","UNKNOWN","50")); assertThat(parser.evaluate(config(),noMissing).passed()).isFalse();
    }
    @Test void obviousSecretsOversizedSamplesAndUnsafeExpectedNumbersAreRejectedWithoutEcho() {
        for(String payload:List.of("{\"password\":\"synthetic-only\"}","{\"nested\":{\"Authorization\":\"synthetic-only\"}}","{token:broken","{\"secret_key\":null}")) {
            assertThatThrownBy(()->parser.validateSamples(List.of(sample("secret","MALFORMED",payload,"UNKNOWN",null)))).isInstanceOf(ApiError.class).hasMessageNotContaining("synthetic-only");
        }
        assertThatThrownBy(()->parser.validateSamples(List.of(sample("oversized","MALFORMED","x".repeat(ProductLimits.MAX_SAMPLE_BYTES+1),"UNKNOWN",null)))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->parser.validateSamples(Collections.nCopies(ProductLimits.MAX_SAMPLES+1,full().getFirst()))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->parser.validateSamples(List.of(sample("unsafe","FIRE","{}","FIRE","1e-1000000")))).isInstanceOf(ApiError.class);
    }
    @Test void sixtyFourKilobyteNonSecretInputsAreScannedWithinSmallLinearTimeBudget() {
        String valid="{\"padding\":\""+"a".repeat(ProductLimits.MAX_SAMPLE_BYTES-14)+"\"}";
        var cases=new ArrayList<>(full()); cases.add(sample("long-valid","UNKNOWN",valid,"UNKNOWN",null)); cases.add(sample("long-malformed","MALFORMED","a".repeat(ProductLimits.MAX_SAMPLE_BYTES),"UNKNOWN",null));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->assertThat(parser.evaluate(config(),cases).passed()).isTrue());
    }
    @Test void unsupportedOrPendingBatteryCannotOutputUsableValueAndEscapedPointerIsLiteral() throws Exception {
        for(String state:List.of("UNSUPPORTED","PENDING")) { var caps=caps(); caps.put("battery",state); assertThat(parser.parse(parser.validate(schema(),caps),"{\"event\":\"FIRE\",\"battery\":0}").batteryPercent()).isNull(); }
        var missing=schema(); missing.set("battery",NullNode.instance); assertThatThrownBy(()->parser.validate(missing,caps())).isInstanceOf(ApiError.class);
        var schema=schema(); schema.put("event_pointer","/a~1b/~0event"); assertThat(parser.parse(parser.validate(schema,caps()),"{\"a/b\":{\"~event\":\"FIRE\"}}").eventType()).isEqualTo("FIRE");
        schema.put("event_pointer","/https://invalid.example/script"); assertThat(parser.parse(parser.validate(schema,caps()),"{}").eventType()).isEqualTo("UNKNOWN");
    }
}
