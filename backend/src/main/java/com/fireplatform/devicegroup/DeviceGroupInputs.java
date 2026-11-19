package com.fireplatform.devicegroup;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fireplatform.shared.*;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DeviceGroupInputs {
    public static final long MAX_SAFE=9_007_199_254_740_991L;
    private final ObjectReader reader;
    public DeviceGroupInputs(ObjectMapper mapper){reader=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    public record Metadata(String name,String description,Long expectedRevision) { }
    public record Membership(String deviceId,long expectedRevision) { }
    JsonNode object(byte[] raw,Set<String> keys){
        try{JsonNode node=reader.readValue(raw);if(node==null||!node.isObject()||node.size()!=keys.size())throw ApiError.bad("分组请求字段无效");var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);if(!actual.equals(keys))throw ApiError.bad("分组请求字段无效");return node;}catch(IOException e){throw ApiError.bad("分组JSON格式无效");}
    }
    static String string(JsonNode node,String key){var value=node.get(key);if(value==null||!value.isTextual())throw ApiError.bad("分组字段须为字符串");return value.textValue();}
    static long integer(JsonNode value){if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0||value.longValue()>MAX_SAFE)throw ApiError.bad("分组版本须为非负安全JSON整数");return value.longValue();}
    public static void unicode(String value){
        if(value==null)throw ApiError.bad("分组文本不可为null");
        try{StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));}catch(CharacterCodingException e){throw ApiError.bad("分组文本编码无效");}
    }
    public static String name(String raw){unicode(raw);if(raw.chars().anyMatch(Character::isISOControl))throw ApiError.bad("分组名称不能包含控制字符");String value=raw.trim();if(value.isEmpty()||value.length()>Limits.DEVICE_GROUP_NAME_MAX)throw ApiError.bad("分组名称长度无效");return value;}
    public static String description(String raw){unicode(raw);if(raw.indexOf(0)>=0)throw ApiError.bad("分组描述含无效字符");String value=raw.trim();if(value.length()>Limits.DEVICE_GROUP_DESCRIPTION_MAX)throw ApiError.bad("分组描述过长");return value;}
    public static String deviceId(String raw){unicode(raw);if(raw.isEmpty()||raw.length()>64||raw.chars().anyMatch(Character::isISOControl))throw ApiError.bad("设备引用无效");return raw;}
    public Metadata metadata(byte[] raw,boolean edit){var node=object(raw,edit?Set.of("name","description","expected_revision"):Set.of("name","description"));return new Metadata(name(string(node,"name")),description(string(node,"description")),edit?integer(node.get("expected_revision")):null);}
    public Membership membership(byte[] raw){var node=object(raw,Set.of("device_id","expected_revision"));return new Membership(deviceId(string(node,"device_id")),integer(node.get("expected_revision")));}
}
