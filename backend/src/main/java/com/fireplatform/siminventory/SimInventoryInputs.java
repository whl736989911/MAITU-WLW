package com.fireplatform.siminventory;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.*;
import com.fireplatform.shared.*;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class SimInventoryInputs {
    public static final long MAX_SAFE=9_007_199_254_740_991L;
    public static final Set<String> REGISTERED=Set.of("AVAILABLE","ISSUED","SCRAPPED");
    private static final Set<String> KEYS=Set.of("expected_inventory_revision","reason"),ISSUE_KEYS=Set.of("expected_inventory_revision","reason","issued_to");
    public enum Operation {RECEIVE("AVAILABLE"),ISSUE("ISSUED"),RECOVER("AVAILABLE"),SCRAP("SCRAPPED"),CORRECT("AVAILABLE");private final String target;Operation(String target){this.target=target;}public String target(){return target;}public String namespace(){return "sim-inventory-"+name().toLowerCase(Locale.ROOT);}}
    public record Command(long expectedInventoryRevision,String issuedTo,String reason){ }
    private final ObjectReader reader;
    public SimInventoryInputs(ObjectMapper mapper){reader=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    public static void unicode(String value){if(value==null)throw ApiError.bad("库存字段不可为null");try{StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));}catch(CharacterCodingException e){throw ApiError.bad("库存文本编码无效");}}
    public static String reason(String value){unicode(value);if(value.length()>Limits.MAX_CHANGE_REASON_LENGTH||value.chars().anyMatch(c->Character.isISOControl(c)&&c!='\r'&&c!='\n'&&c!='\t'))throw ApiError.bad("库存登记依据无效");return value;}
    public static String issuedTo(String value){unicode(value);if(value.chars().anyMatch(c->Character.isISOControl(c)&&c!='\r'&&c!='\n'&&c!='\t'))throw ApiError.bad("库存领用去向无效");String normalized=value.trim();if(normalized.isEmpty()||normalized.length()>Limits.MAX_CHANGE_REASON_LENGTH)throw ApiError.bad("库存领用去向长度无效");return normalized;}
    public static boolean allows(Operation operation,String from){if(operation==null||from==null||(!"UNREGISTERED".equals(from)&&!REGISTERED.contains(from)))return false;if(operation==Operation.CORRECT)return "SCRAPPED".equals(from);return !"SCRAPPED".equals(from)||operation==Operation.SCRAP;}
    public static String status(String raw){if(raw==null)return null;if(!REGISTERED.contains(raw))throw ApiError.bad("库存筛选状态无效");return raw;}
    public static void keys(JsonNode node,Set<String> expected){if(node==null||!node.isObject()||node.size()!=expected.size())throw ApiError.bad("库存字段无效");Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw ApiError.bad("库存字段无效");}
    public static String string(JsonNode node,String key){JsonNode value=node.get(key);if(value==null||!value.isTextual())throw ApiError.bad("库存字段须为字符串");return value.textValue();}
    public static String nullableString(JsonNode node,String key){JsonNode value=node.get(key);if(value==null)throw ApiError.bad("库存字段缺失");return value.isNull()?null:string(node,key);}
    public static long integer(JsonNode node){if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<0||node.longValue()>MAX_SAFE)throw ApiError.bad("库存版本须为安全非负JSON整数");return node.longValue();}
    public static JsonParser integerLexemes(JsonParser parser,String field,Supplier<ApiError> failure){return new JsonParserDelegate(parser){@Override public JsonToken nextToken()throws IOException{JsonToken token=super.nextToken();if(token==JsonToken.VALUE_NUMBER_INT&&field.equals(currentName())&&!getText().matches("0|[1-9][0-9]*"))throw failure.get();return token;}};}
    public Command command(byte[] raw,Operation operation){if(operation==null)throw ApiError.bad("库存操作无效");try(JsonParser parser=integerLexemes(reader.getFactory().createParser(raw),"expected_inventory_revision",()->ApiError.bad("库存版本须为安全非负JSON整数"))){JsonNode node=reader.readValue(parser);keys(node,operation==Operation.ISSUE?ISSUE_KEYS:KEYS);String reason=reason(string(node,"reason"));if(operation==Operation.CORRECT&&reason.trim().isEmpty())throw ApiError.bad("更正报废须填写依据");return new Command(integer(node.get("expected_inventory_revision")),operation==Operation.ISSUE?issuedTo(string(node,"issued_to")):"",reason);}catch(IOException e){throw ApiError.bad("库存JSON无效");}}
    public static Integer page(String value,Integer initial){if(value==null)return initial;if(!value.matches("0|[1-9][0-9]*"))throw ApiError.bad("库存分页须为规范整数");try{return Integer.valueOf(value);}catch(NumberFormatException e){throw ApiError.bad("库存分页超界");}}
}
