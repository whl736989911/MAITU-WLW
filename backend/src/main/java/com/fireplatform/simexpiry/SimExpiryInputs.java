package com.fireplatform.simexpiry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.*;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Limits;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import static com.fireplatform.simexpiry.SimExpiryModels.*;

@Component
public class SimExpiryInputs {
    private final ObjectReader reader;
    public SimExpiryInputs(ObjectMapper mapper){reader=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    public static void keys(JsonNode n,Set<String> expected){if(n==null||!n.isObject()||n.size()!=expected.size())throw ApiError.bad("到期提醒请求字段无效");Set<String>a=new HashSet<>();n.fieldNames().forEachRemaining(a::add);if(!a.equals(expected))throw ApiError.bad("到期提醒请求字段无效");}
    private static String str(JsonNode n,String k){JsonNode v=n.get(k);if(v==null||!v.isTextual())throw ApiError.bad("到期提醒文本字段无效");String s=v.textValue();unicode(s);if(s.chars().anyMatch(Character::isISOControl))throw ApiError.bad("到期提醒文本含控制字符");return s;}
    private static String reason(JsonNode n,String k){JsonNode v=n.get(k);if(v==null||!v.isTextual())throw ApiError.bad("到期提醒文本字段无效");String s=v.textValue();unicode(s);if(s.chars().anyMatch(c->Character.isISOControl(c)&&c!='\r'&&c!='\n'&&c!='\t'))throw ApiError.bad("到期提醒文本含控制字符");return s;}
    private static JsonParser strict(JsonParser p){return new JsonParserDelegate(p){@Override public JsonToken nextToken()throws IOException{JsonToken t=super.nextToken();if(t==JsonToken.VALUE_NUMBER_INT&&!getText().matches("0|[1-9][0-9]*"))throw ApiError.bad("到期提醒整数格式无效");return t;}};}
    private static void unicode(String s){try{StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s));}catch(CharacterCodingException e){throw ApiError.bad("到期提醒文本编码无效");}}
    private static long integer(JsonNode n,String k,long min,long max){JsonNode v=n.get(k);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||!v.asText().matches("0|[1-9][0-9]*"))throw ApiError.bad("到期提醒整数无效");long x=v.longValue();if(x<min||x>max)throw ApiError.bad("到期提醒整数超出表示范围");return x;}
    private <T>T parse(byte[] bytes,String ignored,Function<JsonNode,T> build){try(JsonParser parser=reader.getFactory().createParser(bytes)){parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);JsonNode value=reader.readValue(strict(parser));if(value==null)throw ApiError.bad("JSON请求不能为空");return build.apply(value);}catch(ApiError e){throw e;}catch(IOException|RuntimeException e){throw ApiError.bad("到期提醒JSON无效");}}
    private static boolean zone(String value){return value!=null&&java.time.ZoneId.getAvailableZoneIds().contains(value);}
    private static String nullable(JsonNode n,String k){JsonNode value=n.get(k);if(value==null)throw ApiError.bad("到期提醒字段缺失");return value.isNull()?null:str(n,k);}
    public ConfigInput config(byte[] b){return parse(b,"expected_revision",n->{keys(n,Set.of("enabled","timezone","lead_days","repeat_interval_days","repeat_count","expected_revision","reason"));if(!n.get("enabled").isBoolean())throw ApiError.bad("enabled无效");String zone=nullable(n,"timezone"),reason=reason(n,"reason");if(reason.length()>Limits.MAX_CHANGE_REASON_LENGTH)throw ApiError.bad("到期提醒输入无效");Integer lead=numberNullable(n,"lead_days",0),interval=numberNullable(n,"repeat_interval_days",1),count=numberNullable(n,"repeat_count",0);boolean enabled=n.get("enabled").booleanValue();if((zone==null)!=(lead==null)||(zone==null)!=(interval==null)||(zone==null)!=(count==null)||enabled&&zone==null)throw ApiError.bad("启用配置必须完整");if(zone!=null&&!zone(zone))throw ApiError.bad("时区必须为IANA时区");return new ConfigInput(enabled,zone,lead,interval,count,integer(n,"expected_revision",0,9007199254740991L),reason);});}
    private static Integer numberNullable(JsonNode n,String k,int min){JsonNode v=n.get(k);if(v==null)throw ApiError.bad("到期提醒字段缺失");return v.isNull()?null:(int)integer(n,k,min,Integer.MAX_VALUE);}
    public RecipientInput recipient(byte[] b){return parse(b,"",n->{keys(n,Set.of("user_id"));return new RecipientInput(str(n,"user_id"));});}
    public HandleInput handle(byte[] b){return parse(b,"expected_revision",n->{keys(n,Set.of("reason","expected_revision"));String reason=reason(n,"reason");if(reason.length()>Limits.MAX_CHANGE_REASON_LENGTH||reason.isBlank())throw ApiError.bad("处理理由无效");return new HandleInput(reason,integer(n,"expected_revision",0,9007199254740991L));});}
}
