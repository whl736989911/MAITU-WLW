package com.fireplatform.simcard;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.*;
import com.fireplatform.shared.*;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Local strict input grammar: manual identifiers and evidence, never provider facts. */
@Component
public class SimCardInputs {
    public static final long MAX_SAFE=9_007_199_254_740_991L;
    public static final Set<String> REPLACEABILITY=Set.of("UNKNOWN","REPLACEABLE","NOT_REPLACEABLE");
    public static final Set<String> PARTY=Set.of("UNKNOWN","PLATFORM","TENANT","MANUFACTURER","OTHER");
    public static final Set<String> REPORTED_STATUS=Set.of("UNKNOWN","ACTIVE","SUSPENDED","EXPIRED","CANCELLED","OTHER");
    public static final DateTimeFormatter TIMESTAMP=new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
    private static final Set<String> CREATE=Set.of("iccid","carrier_name","replaceability","management_party","fee_responsibility","reported_operator_status","expires_on","evidence_at","source_note");
    private static final Set<String> EDIT=Set.of("carrier_name","replaceability","management_party","fee_responsibility","reported_operator_status","expires_on","evidence_at","source_note","expected_revision");
    private final ObjectReader reader;
    public SimCardInputs(ObjectMapper mapper){reader=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    public record Metadata(String iccid,String carrierName,String replaceability,String managementParty,String feeResponsibility,String reportedOperatorStatus,String expiresOn,String evidenceAt,String sourceNote,Long expectedRevision) { }
    public static void unicode(String value){
        if(value==null)throw ApiError.bad("SIM字段不可为null");
        try{StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));}catch(CharacterCodingException e){throw ApiError.bad("SIM文本编码无效");}
    }
    private static String text(String raw,int max,boolean nonempty,boolean note){
        unicode(raw);
        if(raw.chars().anyMatch(c->Character.isISOControl(c)&&!(note&&(c=='\r'||c=='\n'||c=='\t'))))throw ApiError.bad("SIM文本含无效控制字符");
        String value=raw.trim();if((nonempty&&value.isEmpty())||value.length()>max)throw ApiError.bad("SIM文本长度无效");return value;
    }
    public static String iccid(String raw){return text(raw,Limits.SIM_CARD_ICCID_MAX,true,false);}
    public static String carrier(String raw){return text(raw,Limits.SIM_CARD_CARRIER_MAX,false,false);}
    public static String note(String raw){return text(raw,Limits.SIM_CARD_SOURCE_NOTE_MAX,false,true);}
    public static String choice(String raw,Set<String> values){if(raw==null||!values.contains(raw))throw ApiError.bad("SIM枚举字段无效");return raw;}
    public static String date(String raw){
        if(raw==null)return null;
        try{if(!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new DateTimeException("shape");LocalDate value=LocalDate.parse(raw,DateTimeFormatter.ISO_LOCAL_DATE);if(value.getYear()<1||value.getYear()>9999)throw new DateTimeException("range");return value.toString();}catch(DateTimeException e){throw ApiError.bad("SIM到期日期格式无效");}
    }
    public static String evidence(String raw){
        if(raw==null)return null;
        try{if(!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z"))throw new DateTimeException("shape");OffsetDateTime value=OffsetDateTime.parse(raw,DateTimeFormatter.ISO_OFFSET_DATE_TIME);if(value.getYear()<1||value.getYear()>9999)throw new DateTimeException("range");return TIMESTAMP.format(value.toInstant());}catch(DateTimeException e){throw ApiError.bad("SIM证据时间格式无效");}
    }
    public static long integer(JsonNode value){if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0||value.longValue()>MAX_SAFE)throw ApiError.bad("SIM版本须为非负安全JSON整数");return value.longValue();}
    public static String string(JsonNode node,String key){JsonNode value=node.get(key);if(value==null||!value.isTextual())throw ApiError.bad("SIM字段须为字符串");return value.textValue();}
    public static String nullableString(JsonNode node,String key){JsonNode value=node.get(key);if(value==null)throw ApiError.bad("SIM字段缺失");return value.isNull()?null:string(node,key);}
    public static void keys(JsonNode node,Set<String> expected){if(node==null||!node.isObject()||node.size()!=expected.size())throw ApiError.bad("SIM请求字段无效");Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw ApiError.bad("SIM请求字段无效");}
    /** Inspect the original number token before JsonNode can turn -0 into ordinary 0. */
    public static JsonParser integerLexemes(JsonParser parser,String field,Supplier<ApiError> failure){
        return new JsonParserDelegate(parser){
            @Override public JsonToken nextToken()throws IOException{
                JsonToken token=super.nextToken();
                if(token==JsonToken.VALUE_NUMBER_INT&&field.equals(currentName())&&!getText().matches("0|[1-9][0-9]*"))throw failure.get();
                return token;
            }
        };
    }
    public Metadata metadata(byte[] raw,boolean edit){
        try(JsonParser parser=integerLexemes(reader.getFactory().createParser(raw),"expected_revision",()->ApiError.bad("SIM版本须为非负安全JSON整数"))){
            JsonNode node=reader.readValue(parser);keys(node,edit?EDIT:CREATE);
            return new Metadata(edit?null:iccid(string(node,"iccid")),carrier(string(node,"carrier_name")),choice(string(node,"replaceability"),REPLACEABILITY),choice(string(node,"management_party"),PARTY),choice(string(node,"fee_responsibility"),PARTY),choice(string(node,"reported_operator_status"),REPORTED_STATUS),date(nullableString(node,"expires_on")),evidence(nullableString(node,"evidence_at")),note(string(node,"source_note")),edit?integer(node.get("expected_revision")):null);
        }catch(IOException e){throw ApiError.bad("SIM JSON格式无效");}
    }
}
