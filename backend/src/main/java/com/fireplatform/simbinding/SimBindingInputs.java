package com.fireplatform.simbinding;

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
public class SimBindingInputs {
    public static final long MAX_SAFE=9_007_199_254_740_991L;
    private static final Set<String> KEYS=Set.of("device_id","expected_association_revision","reason");
    private final ObjectReader reader;
    public SimBindingInputs(ObjectMapper mapper){reader=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    public record Command(String deviceId,long expectedAssociationRevision,String reason){ }
    public static void unicode(String value){if(value==null)throw ApiError.bad("关联文本不可为null");try{StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));}catch(CharacterCodingException e){throw ApiError.bad("关联文本编码无效");}}
    public static String device(String value){unicode(value);if(value.isEmpty()||value.length()>64||value.chars().anyMatch(Character::isISOControl)||!value.equals(value.trim()))throw ApiError.bad("设备引用无效");return value;}
    public static String reason(String value){unicode(value);if(value.length()>Limits.MAX_CHANGE_REASON_LENGTH||value.chars().anyMatch(c->Character.isISOControl(c)&&c!='\r'&&c!='\n'&&c!='\t'))throw ApiError.bad("关联理由格式无效");return value;}
    public static void keys(JsonNode value,Set<String> expected){if(value==null||!value.isObject()||value.size()!=expected.size())throw ApiError.bad("关联字段无效");Set<String> actual=new HashSet<>();value.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw ApiError.bad("关联字段无效");}
    public static String string(JsonNode node,String key){JsonNode value=node.get(key);if(value==null||!value.isTextual())throw ApiError.bad("关联字段须为字符串");return value.textValue();}
    public static String nullableString(JsonNode node,String key){JsonNode value=node.get(key);if(value==null)throw ApiError.bad("关联字段缺失");return value.isNull()?null:string(node,key);}
    public static long integer(JsonNode value){if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0||value.longValue()>MAX_SAFE)throw ApiError.bad("关联版本须为安全非负JSON整数");return value.longValue();}
    public static JsonParser integerLexemes(JsonParser parser,String field,Supplier<ApiError> failure){return new JsonParserDelegate(parser){@Override public JsonToken nextToken()throws IOException{JsonToken token=super.nextToken();if(token==JsonToken.VALUE_NUMBER_INT&&field.equals(currentName())&&!getText().matches("0|[1-9][0-9]*"))throw failure.get();return token;}};}
    public Command command(byte[] raw){
        try(JsonParser parser=integerLexemes(reader.getFactory().createParser(raw),"expected_association_revision",()->ApiError.bad("关联版本须为安全非负JSON整数"))){
            JsonNode node=reader.readValue(parser);keys(node,KEYS);
            return new Command(device(string(node,"device_id")),integer(node.get("expected_association_revision")),reason(string(node,"reason")));
        }catch(IOException e){throw ApiError.bad("关联JSON格式无效");}
    }
    public static Integer page(String value,Integer initial){if(value==null)return initial;if(!value.matches("0|[1-9][0-9]*"))throw ApiError.bad("关联分页须为规范整数");try{return Integer.valueOf(value);}catch(NumberFormatException e){throw ApiError.bad("关联分页超出范围");}}
}
