package com.fireplatform.product;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Digests;
import com.fireplatform.shared.Limits;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Pure bounded tree projection. No scripting, reflection, external references, files or network. */
@Service
public class JsonPointerParser {
    private final ObjectMapper mapper;
    private static final Set<String> CAPABILITIES=Set.of("SUPPORTED","UNSUPPORTED","PENDING");
    private static final Set<String> CAPABILITY_KEYS=Set.of("fire","recovery","battery","fault","heartbeat","telemetry");
    private static final Set<String> MAPPING_TYPES=Set.of("FIRE","FAULT","RECOVERY","HEARTBEAT","PARAM","OTHER");
    private static final Set<String> MEASUREMENT_TYPES=Set.of("NUMBER","STRING","BOOLEAN");
    private static final Set<String> MEASUREMENT_FIELDS=Set.of("key","label","pointer","value_type","unit","scale","offset","min","max","required");
    private static final Set<String> MEASUREMENT_REQUIRED=Set.of("key","label","pointer","value_type");
    private static final Pattern KEY=Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern SECRET=Pattern.compile("(?i)(?:password|token|secret|authorization|api[_-]?key|credential)");
    public record Battery(JsonPointer pointer,BigDecimal scale,BigDecimal offset,BigDecimal min,BigDecimal max) { }
    public record MeasurementDef(String key,String label,JsonPointer pointer,String valueType,String unit,BigDecimal scale,BigDecimal offset,BigDecimal min,BigDecimal max,boolean required) { }
    public record Configuration(JsonPointer eventPointer,Map<String,String> mappings,Battery battery,boolean batterySupported,List<MeasurementDef> measurements,Set<String> supportedEvents,boolean telemetrySupported,boolean publishableCapabilities) { }
    public record Outcome(String eventType,BigDecimal batteryPercent,JsonNode rawEvent,List<String> diagnostics,List<ProductModels.MeasurementReading> readings,String sourceEventValue) { }
    public record Evaluation(boolean passed,List<ProductModels.TestRow> results) { }
    public JsonPointerParser() {
        var factory=JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(Limits.JSON_MAX_DEPTH).maxStringLength(Limits.JSON_MAX_STRING).maxNumberLength(Limits.JSON_MAX_NUMBER_LENGTH).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        mapper=new ObjectMapper(factory).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private ApiError invalid() { return ApiError.bad("解析模板、能力或合成样例结构无效/超过资源边界"); }
    private void fields(JsonNode node,Set<String> allowed,Set<String> required) {
        if(node==null || !node.isObject()) throw invalid();
        node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key)) throw invalid();});
        for(String key:required) if(!node.has(key)) throw invalid();
    }
    private JsonPointer pointer(JsonNode node) {
        if(node==null || !node.isTextual()) throw invalid(); String value=node.textValue();
        if(value.length()>ProductLimits.MAX_POINTER_LENGTH || (!value.isEmpty() && !value.startsWith("/")) || value.contains("*") || value.contains("$")) throw invalid();
        int depth=0;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i); if(c=='/' && ++depth>ProductLimits.MAX_POINTER_DEPTH) throw invalid();
            if(c=='~') { if(++i>=value.length() || (value.charAt(i)!='0' && value.charAt(i)!='1')) throw invalid(); }
        }
        for(String token:value.split("/",-1)) if(token.equals("..")) throw invalid();
        return JsonPointer.compile(value);
    }
    private boolean bounded(BigDecimal value,int precision,int scale,BigDecimal maximum) {
        return value!=null && value.precision()<=precision && Math.abs((long)value.scale())<=scale && value.abs().compareTo(maximum)<=0;
    }
    private BigDecimal number(JsonNode node) {
        if(node==null || !node.isNumber()) throw invalid();
        try { var value=node.decimalValue(); if(!bounded(value,ProductLimits.MAX_DECIMAL_PRECISION,ProductLimits.MAX_DECIMAL_SCALE,ProductLimits.MAX_ABSOLUTE)) throw invalid(); return value; }
        catch(NumberFormatException | ArithmeticException e) { throw invalid(); }
    }
    private String rawKey(JsonNode node) {
        if(node==null || node.isNull()) throw invalid();
        if(node.isTextual()) { if(node.textValue().length()>ProductLimits.MAX_RAW_STRING) throw invalid(); return "s:"+node.textValue(); }
        if(node.isBoolean()) return "b:"+node.booleanValue();
        if(node.isNumber()) return "n:"+number(node).stripTrailingZeros().toPlainString();
        throw invalid();
    }
    private static String text(JsonNode node) { return node!=null && node.isTextual() ? node.textValue() : null; }
    private static boolean present(JsonNode node,String field) { return node.has(field) && !node.get(field).isNull(); }
    private String capability(JsonNode capabilities,String key) { var value=capabilities.get(key); return value==null || value.isNull() ? null : value.textValue(); }
    private List<MeasurementDef> measurements(JsonNode node) {
        if(node==null || node.isNull()) return List.of();
        if(!node.isArray() || node.size()>ProductLimits.MAX_MEASUREMENTS) throw invalid();
        var definitions=new ArrayList<MeasurementDef>(node.size()); var keys=new HashSet<String>();
        for(JsonNode entry:node) {
            fields(entry,MEASUREMENT_FIELDS,MEASUREMENT_REQUIRED);
            String key=text(entry.get("key"));
            if(key==null || !KEY.matcher(key).matches() || !keys.add(key) || SECRET.matcher(key).find()) throw invalid();
            String label=text(entry.get("label"));
            if(label==null || label.isBlank() || label.length()>ProductLimits.MAX_MEASUREMENT_LABEL || SECRET.matcher(label).find()) throw invalid();
            JsonPointer pointer=pointer(entry.get("pointer"));
            String type=text(entry.get("value_type"));
            if(type==null || !MEASUREMENT_TYPES.contains(type)) throw invalid();
            String unit=null;
            if(present(entry,"unit")) {
                unit=text(entry.get("unit"));
                if(unit==null || unit.isBlank() || unit.length()>ProductLimits.MAX_MEASUREMENT_UNIT || SECRET.matcher(unit).find()) throw invalid();
            }
            BigDecimal scale=null,offset=null,min=null,max=null;
            if("NUMBER".equals(type)) {
                if(present(entry,"scale")) scale=number(entry.get("scale"));
                if(present(entry,"offset")) offset=number(entry.get("offset"));
                if(present(entry,"min")) min=number(entry.get("min"));
                if(present(entry,"max")) max=number(entry.get("max"));
                if(scale!=null && scale.signum()<=0) throw invalid();
                if(min!=null && max!=null && min.compareTo(max)>0) throw invalid();
            } else for(String field:List.of("scale","offset","min","max")) if(present(entry,field)) throw invalid();
            boolean required=false;
            if(present(entry,"required")) { var flag=entry.get("required"); if(!flag.isBoolean()) throw invalid(); required=flag.booleanValue(); }
            definitions.add(new MeasurementDef(key,label,pointer,type,unit,scale,offset,min,max,required));
        }
        return List.copyOf(definitions);
    }
    public Configuration validate(JsonNode schema,JsonNode capabilities) {
        if(schema==null || schema.toString().getBytes(StandardCharsets.UTF_8).length>ProductLimits.MAX_SCHEMA_BYTES) throw invalid();
        fields(schema,Set.of("format","event_pointer","event_mappings","battery","measurements"),Set.of("format"));
        if(!schema.path("format").isTextual() || !schema.path("format").textValue().equals("JSON_POINTER_V1")) throw invalid();
        JsonPointer event=null;
        if(present(schema,"event_pointer")) event=pointer(schema.get("event_pointer"));
        var mappings=new HashMap<String,String>();
        JsonNode entries=schema.get("event_mappings");
        if(entries!=null && !entries.isNull()) {
            if(!entries.isArray() || entries.size()>ProductLimits.MAX_MAPPINGS) throw invalid();
            for(JsonNode entry:entries) {
                fields(entry,Set.of("raw_value","event_type"),Set.of("raw_value","event_type")); var type=entry.get("event_type");
                if(!type.isTextual() || !MAPPING_TYPES.contains(type.textValue())) throw invalid();
                if(mappings.putIfAbsent(rawKey(entry.get("raw_value")),type.textValue())!=null) throw invalid();
            }
            if(!entries.isEmpty() && event==null) throw invalid();
        }
        fields(capabilities,CAPABILITY_KEYS,Set.of("fire","recovery","battery"));
        for(String key:CAPABILITY_KEYS) if(capabilities.has(key)) { var value=capabilities.get(key); if(!value.isTextual() || !CAPABILITIES.contains(value.textValue())) throw invalid(); }
        Battery battery=null; JsonNode b=schema.get("battery");
        if(b!=null && !b.isNull()) {
            fields(b,Set.of("pointer","scale","offset","min","max"),Set.of("pointer","scale","offset","min","max"));
            var scale=number(b.get("scale")); var offset=number(b.get("offset")); var min=number(b.get("min")); var max=number(b.get("max"));
            if(scale.signum()<=0 || min.signum()<0 || max.compareTo(BigDecimal.valueOf(100))>0 || min.compareTo(max)>0) throw invalid();
            battery=new Battery(pointer(b.get("pointer")),scale,offset,min,max);
        }
        List<MeasurementDef> measurements=measurements(schema.get("measurements"));
        boolean batterySupported="SUPPORTED".equals(capability(capabilities,"battery"));
        if(batterySupported && battery==null) throw invalid();
        boolean telemetrySupported="SUPPORTED".equals(capability(capabilities,"telemetry"));
        String telemetryCapability=capability(capabilities,"telemetry");
        if(!measurements.isEmpty() && (telemetryCapability==null || "UNSUPPORTED".equals(telemetryCapability))) throw invalid();
        if(telemetrySupported && measurements.isEmpty()) throw invalid();
        if(event==null && measurements.isEmpty() && battery==null) throw invalid();
        var supportedEvents=new HashSet<String>(); supportedEvents.add("OTHER");
        if("SUPPORTED".equals(capability(capabilities,"fire"))) supportedEvents.add("FIRE");
        if("SUPPORTED".equals(capability(capabilities,"recovery"))) supportedEvents.add("RECOVERY");
        if("SUPPORTED".equals(capability(capabilities,"fault"))) supportedEvents.add("FAULT");
        if("SUPPORTED".equals(capability(capabilities,"heartbeat"))) supportedEvents.add("HEARTBEAT");
        if(telemetrySupported) supportedEvents.add("PARAM");
        for(String type:List.of("FIRE","RECOVERY","FAULT","HEARTBEAT")) if(supportedEvents.contains(type) && !mappings.containsValue(type)) throw invalid();
        boolean publishable=true, anySupported=false;
        for(String key:CAPABILITY_KEYS) {
            String value=capability(capabilities,key); if(value==null) continue;
            if("PENDING".equals(value)) publishable=false;
            if("SUPPORTED".equals(value)) anySupported=true;
        }
        publishable&=anySupported;
        return new Configuration(event,Map.copyOf(mappings),battery,batterySupported,measurements,Set.copyOf(supportedEvents),telemetrySupported,publishable);
    }
    public JsonNode defaultSchema() {
        try { return mapper.readTree("{\"format\":\"JSON_POINTER_V1\",\"event_pointer\":\"/event\",\"event_mappings\":[{\"raw_value\":\"FIRE\",\"event_type\":\"FIRE\"},{\"raw_value\":\"RECOVERY\",\"event_type\":\"RECOVERY\"}],\"battery\":{\"pointer\":\"/battery\",\"scale\":1,\"offset\":0,\"min\":0,\"max\":100}}"); } catch(JsonProcessingException e) { throw new IllegalStateException("Default template invalid"); }
    }
    public JsonNode pendingCapabilities() { return mapper.createObjectNode().put("fire","PENDING").put("recovery","PENDING").put("battery","PENDING"); }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()) { var out=mapper.createObjectNode(); var keys=new TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add); for(String key:keys) out.set(key,canonical(node.get(key))); return out; }
        if(node.isArray()) { var out=mapper.createArrayNode(); for(JsonNode item:node) out.add(canonical(item)); return out; } return node;
    }
    public String fingerprint(JsonNode schema,JsonNode capabilities) { return fingerprint(schema,capabilities,null); }
    public String fingerprint(JsonNode schema,JsonNode capabilities,ProductModels.BatteryThreshold battery) { var root=mapper.createObjectNode(); root.set("battery_low_threshold",battery==null ? mapper.nullNode() : mapper.valueToTree(battery)); root.set("capabilities",canonical(capabilities)); root.set("schema",canonical(schema)); return Digests.sha256(root.toString()); }
    private void noSecrets(JsonNode node) {
        if(node.isObject()) node.fields().forEachRemaining(entry->{ if(SECRET.matcher(entry.getKey()).find()) throw invalid(); noSecrets(entry.getValue()); });
        else if(node.isArray()) for(JsonNode child:node) noSecrets(child);
    }
    /** Linear lexical fallback for obvious malformed/unquoted keys; no unbounded look-behind or regex retry. */
    private void malformedSecretScan(String payload) {
        var token=new StringBuilder(); char quote=0; boolean escaped=false;
        for(int i=0;i<payload.length();i++) {
            char c=payload.charAt(i);
            if(quote!=0) {
                if(escaped) { token.append(c); escaped=false; }
                else if(c=='\\') { token.append(c); escaped=true; }
                else if(c==quote) quote=0;
                else token.append(c);
            } else if(c=='\"' || c=='\'') { token.setLength(0); quote=c; }
            else if(c==':') { if(SECRET.matcher(token).find()) throw invalid(); token.setLength(0); }
            else if(c=='{' || c=='}' || c=='[' || c==']' || c==',') token.setLength(0);
            else if(!Character.isWhitespace(c)) token.append(c);
        }
    }
    private void validateSecretFields(String payload) {
        malformedSecretScan(payload);
        try(var scan=mapper.getFactory().createParser(payload)) {
            while(scan.nextToken()!=null) if(scan.currentToken()==JsonToken.FIELD_NAME && SECRET.matcher(scan.currentName()).find()) throw invalid();
        } catch(java.io.IOException ignored) { /* Malformed synthetic JSON remains a business test case. */ }
    }
    /** Shared bounded safety policy for a raw frame; this is not a synthetic test or a business application. */
    public void validatePayload(String payload) {
        if(payload==null || payload.getBytes(StandardCharsets.UTF_8).length>ProductLimits.MAX_SAMPLE_BYTES) throw invalid();
        validateSecretFields(payload);
    }
    public void validateSamples(List<ProductInputs.Sample> samples) {
        if(samples==null || samples.isEmpty() || samples.size()>ProductLimits.MAX_SAMPLES) throw invalid(); var names=new HashSet<String>();
        for(var sample:samples) {
            if(sample==null || sample.name()==null || sample.name().isBlank() || sample.name().length()>100 || !names.add(sample.name()) || sample.caseKind()==null || sample.expectedEvent()==null || !Set.of("FIRE","FAULT","RECOVERY","HEARTBEAT","PARAM","OTHER","UNKNOWN","MALFORMED").contains(sample.caseKind()) || !Set.of("FIRE","FAULT","RECOVERY","HEARTBEAT","PARAM","OTHER","UNKNOWN").contains(sample.expectedEvent()) || sample.payload()==null) throw invalid();
            if(sample.payload().getBytes(StandardCharsets.UTF_8).length>ProductLimits.MAX_SAMPLE_BYTES) throw invalid();
            if(sample.expectedBatteryPercent()!=null && (!bounded(sample.expectedBatteryPercent(),ProductLimits.MAX_RESULT_PRECISION,ProductLimits.MAX_RESULT_SCALE,BigDecimal.valueOf(100)) || sample.expectedBatteryPercent().signum()<0)) throw invalid();
            var readings=sample.expectedMeasurements();
            if(readings!=null) {
                if(readings.size()>ProductLimits.MAX_SAMPLE_READINGS) throw invalid(); var keys=new HashSet<String>();
                for(var reading:readings) {
                    if(reading==null || reading.key()==null || !KEY.matcher(reading.key()).matches() || !keys.add(reading.key()) || reading.state()==null || !Set.of("VALID","MISSING","INVALID").contains(reading.state())) throw invalid();
                    JsonNode value=reading.value();
                    if("VALID".equals(reading.state())) {
                        if(value==null || value.isNull()) throw invalid();
                        if(value.isNumber()) { if(!bounded(value.decimalValue(),ProductLimits.MAX_RESULT_PRECISION,ProductLimits.MAX_RESULT_SCALE,ProductLimits.MAX_ABSOLUTE)) throw invalid(); }
                        else if(value.isTextual()) { if(value.textValue().length()>ProductLimits.MAX_RAW_STRING) throw invalid(); }
                        else if(!value.isBoolean()) throw invalid();
                    } else if(value!=null && !value.isNull()) throw invalid();
                }
            }
            validateSecretFields(sample.payload());
        }
    }
    /** Resolve one declared measurement exactly; throws the shared bounded error for absence of a usable value. */
    private JsonNode measure(JsonNode node,MeasurementDef definition) {
        return switch(definition.valueType()) {
            case "NUMBER" -> {
                if(!node.isNumber()) throw invalid();
                BigDecimal transformed=number(node);
                if(definition.scale()!=null) transformed=transformed.multiply(definition.scale());
                if(definition.offset()!=null) transformed=transformed.add(definition.offset());
                if(!bounded(transformed,ProductLimits.MAX_RESULT_PRECISION,ProductLimits.MAX_RESULT_SCALE,ProductLimits.MAX_ABSOLUTE)) throw invalid();
                if(definition.min()!=null && transformed.compareTo(definition.min())<0 || definition.max()!=null && transformed.compareTo(definition.max())>0) throw invalid();
                // Match the JSON node type a persisted BigDecimal round-trip produces: an integral value is a
                // BigInteger token, a fractional value is a decimal token. This keeps retained evidence comparable.
                BigDecimal normalized=transformed.scale()<0?transformed.setScale(0):transformed;
                yield normalized.scale()==0?BigIntegerNode.valueOf(normalized.toBigIntegerExact()):DecimalNode.valueOf(normalized);
            }
            case "STRING" -> {
                if(!node.isTextual() || node.textValue().length()>ProductLimits.MAX_RAW_STRING) throw invalid();
                yield TextNode.valueOf(node.textValue());
            }
            case "BOOLEAN" -> {
                if(!node.isBoolean()) throw invalid();
                yield BooleanNode.valueOf(node.booleanValue());
            }
            default -> throw invalid();
        };
    }
    private String sourceValue(JsonNode raw) {
        if(raw==null || raw.isMissingNode() || raw.isNull()) return null;
        try {
            String text=raw.isNumber()?number(raw).toPlainString():raw.isValueNode()?raw.asText():null;
            return text==null || text.length()>ProductLimits.MAX_RAW_STRING ? null : text;
        } catch(ApiError unusable) { return null; }
    }
    public Outcome parse(Configuration config,String payload) {
        if(payload==null || payload.getBytes(StandardCharsets.UTF_8).length>ProductLimits.MAX_SAMPLE_BYTES) throw invalid();
        JsonNode tree;
        try { tree=mapper.readTree(payload); if(tree==null) throw new IllegalArgumentException(); }
        catch(JsonProcessingException | IllegalArgumentException e) { return new Outcome("UNKNOWN",null,NullNode.instance,List.of("PARSE_ERROR"),List.of(),null); }
        noSecrets(tree); var diagnostics=new ArrayList<String>(); JsonNode raw=config.eventPointer()==null?NullNode.instance:tree.at(config.eventPointer()); String event="UNKNOWN";
        if(config.eventPointer()!=null) {
            if(raw.isMissingNode()) diagnostics.add("EVENT_MISSING"); else if(raw.isNull()) diagnostics.add("EVENT_NULL");
            else {
                try {
                    String mapped=config.mappings().get(rawKey(raw));
                    if(mapped==null) diagnostics.add("EVENT_UNMAPPED");
                    else if(!config.supportedEvents().contains(mapped)) diagnostics.add("EVENT_CAPABILITY_UNSUPPORTED");
                    else event=mapped;
                } catch(ApiError e) { diagnostics.add("EVENT_TYPE_OR_NUMERIC_LIMIT"); }
            }
        }
        String sourceEventValue=sourceValue(raw);
        BigDecimal battery=null;
        if(!config.batterySupported()) diagnostics.add("BATTERY_UNAVAILABLE");
        else {
            var b=config.battery(); JsonNode rawBattery=tree.at(b.pointer());
            if(rawBattery.isMissingNode() || rawBattery.isNull()) diagnostics.add("BATTERY_MISSING");
            else if(!rawBattery.isNumber()) diagnostics.add("BATTERY_NOT_NUMBER");
            else {
                try {
                    var transformed=number(rawBattery).multiply(b.scale()).add(b.offset());
                    if(transformed.compareTo(b.min())<0 || transformed.compareTo(b.max())>0) diagnostics.add("BATTERY_OUT_OF_RANGE"); else battery=transformed;
                } catch(ApiError e) { diagnostics.add("BATTERY_NUMERIC_LIMIT"); }
            }
        }
        var readings=new ArrayList<ProductModels.MeasurementReading>(config.measurements().size());
        for(MeasurementDef definition:config.measurements()) {
            JsonNode value=tree.at(definition.pointer());
            if(value.isMissingNode() || value.isNull()) { diagnostics.add("MEASUREMENT_MISSING:"+definition.key()); continue; }
            try { readings.add(new ProductModels.MeasurementReading(definition.key(),definition.label(),definition.valueType(),measure(value,definition),definition.unit())); }
            catch(ApiError unusable) { diagnostics.add("MEASUREMENT_INVALID:"+definition.key()); }
        }
        if("UNKNOWN".equals(event) && config.telemetrySupported() && !readings.isEmpty()) event="PARAM";
        return new Outcome(event,battery,raw.isMissingNode() ? NullNode.instance : raw,List.copyOf(diagnostics),List.copyOf(readings),sourceEventValue);
    }
    private boolean matches(MeasurementDef definition,JsonNode expected,JsonNode actual) {
        if(expected==null || actual==null) return false;
        return switch(definition.valueType()) {
            case "NUMBER" -> expected.isNumber() && actual.isNumber() && expected.decimalValue().compareTo(actual.decimalValue())==0;
            case "STRING" -> expected.isTextual() && actual.isTextual() && expected.textValue().equals(actual.textValue());
            case "BOOLEAN" -> expected.isBoolean() && actual.isBoolean() && expected.booleanValue()==actual.booleanValue();
            default -> false;
        };
    }
    public Evaluation evaluate(Configuration config,List<ProductInputs.Sample> samples) {
        validateSamples(samples); var rows=new ArrayList<ProductModels.TestRow>();
        var covered=new HashSet<String>(); var validMeasurementKeys=new HashSet<String>(); boolean validBattery=false,nullBattery=false,sawMissing=false,sawInvalid=false;
        var definitions=new HashMap<String,MeasurementDef>(); for(var definition:config.measurements()) definitions.put(definition.key(),definition);
        for(var sample:samples) {
            var actual=parse(config,sample.payload()); var diagnostics=new ArrayList<>(actual.diagnostics()); boolean malformed=diagnostics.contains("PARSE_ERROR");
            boolean kind=switch(sample.caseKind()) { case "MALFORMED" -> malformed; case "UNKNOWN" -> !malformed && actual.eventType().equals("UNKNOWN"); default -> !malformed && actual.eventType().equals(sample.caseKind()); };
            boolean batteryMatches=actual.batteryPercent()==null ? sample.expectedBatteryPercent()==null : sample.expectedBatteryPercent()!=null && actual.batteryPercent().compareTo(sample.expectedBatteryPercent())==0;
            var expected=new LinkedHashMap<String,ProductInputs.SampleReading>();
            if(sample.expectedMeasurements()!=null) for(var reading:sample.expectedMeasurements()) expected.putIfAbsent(reading.key(),reading);
            var actualReadings=new LinkedHashMap<String,ProductModels.MeasurementReading>();
            for(var reading:actual.readings()) actualReadings.put(reading.key(),reading);
            var problems=new ArrayList<String>();
            for(String key:actualReadings.keySet()) if(!expected.containsKey(key)) problems.add("UNEXPECTED_MEASUREMENT:"+key);
            for(var entry:expected.entrySet()) {
                String key=entry.getKey(); var expectation=entry.getValue(); var reading=actualReadings.get(key); var definition=definitions.get(key);
                if(definition==null) { problems.add("UNKNOWN_MEASUREMENT:"+key); continue; }
                switch(expectation.state()) {
                    case "VALID" -> { if(reading==null) problems.add("EXPECTED_MEASUREMENT_MISSING:"+key); else if(!matches(definition,expectation.value(),reading.value())) problems.add("EXPECTED_MEASUREMENT_MISMATCH:"+key); }
                    case "MISSING" -> { if(reading!=null) problems.add("UNEXPECTED_MEASUREMENT:"+key); if(!actual.diagnostics().contains("MEASUREMENT_MISSING:"+key)) problems.add("EXPECTED_MEASUREMENT_MISSING:"+key); }
                    default -> { if(reading!=null) problems.add("UNEXPECTED_MEASUREMENT:"+key); if(!actual.diagnostics().contains("MEASUREMENT_INVALID:"+key)) problems.add("EXPECTED_MEASUREMENT_INVALID:"+key); }
                }
            }
            diagnostics.addAll(problems);
            boolean passed=kind && actual.eventType().equals(sample.expectedEvent()) && batteryMatches && problems.isEmpty();
            if(passed) {
                covered.add(sample.caseKind());
                if(actual.batteryPercent()!=null) validBattery=true; if(!malformed && actual.batteryPercent()==null) nullBattery=true;
                for(var entry:expected.entrySet()) {
                    if("VALID".equals(entry.getValue().state()) && actualReadings.containsKey(entry.getKey())) validMeasurementKeys.add(entry.getKey());
                    if("MISSING".equals(entry.getValue().state())) sawMissing=true;
                    if("INVALID".equals(entry.getValue().state())) sawInvalid=true;
                }
            }
            rows.add(new ProductModels.TestRow(sample.name(),passed,actual.eventType(),actual.batteryPercent(),actual.readings(),actual.rawEvent(),List.copyOf(diagnostics)));
        }
        var required=new LinkedHashSet<String>(List.of("UNKNOWN","MALFORMED"));
        for(String type:List.of("FIRE","RECOVERY","FAULT","HEARTBEAT")) if(config.supportedEvents().contains(type)) required.add(type);
        if(config.telemetrySupported()) required.add("PARAM");
        boolean measurementsOk=true;
        if(!config.measurements().isEmpty()) {
            measurementsOk=covered.contains("PARAM") && sawMissing && sawInvalid;
            for(var definition:config.measurements()) if(definition.required()) measurementsOk&=validMeasurementKeys.contains(definition.key());
        }
        boolean passed=rows.stream().allMatch(ProductModels.TestRow::passed) && covered.containsAll(required) && measurementsOk && (!config.batterySupported() || validBattery) && nullBattery && config.publishableCapabilities();
        return new Evaluation(passed,List.copyOf(rows));
    }
}
