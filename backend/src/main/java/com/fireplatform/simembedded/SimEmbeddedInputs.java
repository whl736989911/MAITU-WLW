package com.fireplatform.simembedded;

import com.fasterxml.jackson.core.*;
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

@Component
public class SimEmbeddedInputs {
    public static final long MAX_SAFE=9_007_199_254_740_991L;
    public static final Set<String> REPLACEABILITY=Set.of("UNKNOWN","REPLACEABLE","NOT_REPLACEABLE"),PARTY=Set.of("UNKNOWN","PLATFORM","TENANT","MANUFACTURER","OTHER"),REPORTED_STATUS=Set.of("UNKNOWN","ACTIVE","SUSPENDED","EXPIRED","CANCELLED","OTHER");
    public static final DateTimeFormatter TIME=new DateTimeFormatterBuilder().appendInstant(6).toFormatter(Locale.ROOT);
    private static final Set<String> KEYS=Set.of("device_id","iccid","carrier_name","replaceability","management_party","fee_responsibility","reported_operator_status","expires_on","evidence_at","source_note","expected_profile_revision","reason");
    private final ObjectReader reader;
    public SimEmbeddedInputs(ObjectMapper mapper){reader=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    public record Command(String deviceId,String iccid,String carrierName,String replaceability,String managementParty,String feeResponsibility,String reportedOperatorStatus,String expiresOn,String evidenceAt,String sourceNote,long expectedProfileRevision,String reason){ }
    public static void unicode(String value){if(value==null)throw ApiError.bad("内置资料字段不可为null");try{StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));}catch(CharacterCodingException e){throw ApiError.bad("内置资料文本编码无效");}}
    private static String text(String value,int max,boolean nonempty,boolean note){unicode(value);if(value.chars().anyMatch(c->Character.isISOControl(c)&&!(note&&(c=='\r'||c=='\n'||c=='\t'))))throw ApiError.bad("内置资料含无效控制字符");String normalized=value.trim();if(normalized.length()>max||(nonempty&&normalized.isEmpty()))throw ApiError.bad("内置资料文本长度无效");return normalized;}
    public static String device(String value){unicode(value);if(value.isEmpty()||value.length()>64||!value.equals(value.trim())||value.chars().anyMatch(Character::isISOControl))throw ApiError.bad("设备引用无效");return value;}
    public static String iccid(String value){return value==null?null:text(value,Limits.SIM_CARD_ICCID_MAX,true,false);}
    public static String carrier(String value){return text(value,Limits.SIM_CARD_CARRIER_MAX,false,false);}
    public static String note(String value){return text(value,Limits.SIM_CARD_SOURCE_NOTE_MAX,false,true);}
    public static String reason(String value){unicode(value);if(value.length()>Limits.MAX_CHANGE_REASON_LENGTH||value.chars().anyMatch(c->Character.isISOControl(c)&&c!='\r'&&c!='\n'&&c!='\t'))throw ApiError.bad("登记依据格式无效");return value;}
    public static String choice(String value,Set<String> choices){if(value==null||!choices.contains(value))throw ApiError.bad("内置资料枚举无效");return value;}
    public static String date(String value){if(value==null)return null;try{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new DateTimeException("shape");LocalDate date=LocalDate.parse(value,DateTimeFormatter.ISO_LOCAL_DATE);if(date.getYear()<1||date.getYear()>9999)throw new DateTimeException("range");return date.toString();}catch(DateTimeException e){throw ApiError.bad("到期日期无效");}}
    public static String evidence(String value){if(value==null)return null;try{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z"))throw new DateTimeException("shape");OffsetDateTime time=OffsetDateTime.parse(value,DateTimeFormatter.ISO_OFFSET_DATE_TIME);if(time.getYear()<1||time.getYear()>9999)throw new DateTimeException("range");return TIME.format(time.toInstant());}catch(DateTimeException e){throw ApiError.bad("证据时间无效");}}
    public static void keys(JsonNode node,Set<String> expected){if(node==null||!node.isObject()||node.size()!=expected.size())throw ApiError.bad("内置资料字段无效");Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw ApiError.bad("内置资料字段无效");}
    public static String string(JsonNode node,String key){JsonNode value=node.get(key);if(value==null||!value.isTextual())throw ApiError.bad("内置资料字段须为字符串");return value.textValue();}
    public static String nullableString(JsonNode node,String key){JsonNode value=node.get(key);if(value==null)throw ApiError.bad("内置资料字段缺失");return value.isNull()?null:string(node,key);}
    public static long integer(JsonNode node){if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<0||node.longValue()>MAX_SAFE)throw ApiError.bad("资料版本须为非负安全JSON整数");return node.longValue();}
    public static JsonParser integerLexemes(JsonParser parser,String field,Supplier<ApiError> failure){return new JsonParserDelegate(parser){@Override public JsonToken nextToken()throws IOException{JsonToken token=super.nextToken();if(token==JsonToken.VALUE_NUMBER_INT&&field.equals(currentName())&&!getText().matches("0|[1-9][0-9]*"))throw failure.get();return token;}};}
    public Command command(byte[] raw){try(JsonParser parser=integerLexemes(reader.getFactory().createParser(raw),"expected_profile_revision",()->ApiError.bad("资料版本须为非负安全JSON整数"))){JsonNode node=reader.readValue(parser);keys(node,KEYS);return new Command(device(string(node,"device_id")),iccid(nullableString(node,"iccid")),carrier(string(node,"carrier_name")),choice(string(node,"replaceability"),REPLACEABILITY),choice(string(node,"management_party"),PARTY),choice(string(node,"fee_responsibility"),PARTY),choice(string(node,"reported_operator_status"),REPORTED_STATUS),date(nullableString(node,"expires_on")),evidence(nullableString(node,"evidence_at")),note(string(node,"source_note")),integer(node.get("expected_profile_revision")),reason(string(node,"reason")));}catch(IOException e){throw ApiError.bad("内置资料JSON无效");}}
    public static Integer page(String value,Integer initial){if(value==null)return initial;if(!value.matches("0|[1-9][0-9]*"))throw ApiError.bad("资料分页须为规范整数");try{return Integer.valueOf(value);}catch(NumberFormatException e){throw ApiError.bad("资料分页超界");}}
}
