package com.fireplatform.alarm;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fireplatform.shared.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Non-secret coordinates, not a signature or authority. Canonical UTF-8 JSON and unpadded base64url. */
public final class AlarmChangeCursor {
    public static final String SESSION_NAMESPACE="alarm-changes:v1:";
    private static final int MAX_ENCODED_BYTES=2048;
    private static final ObjectMapper JSON=new ObjectMapper(new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Position(String tenantId,String audience,String session,String streamId,long sequence) { }
    private AlarmChangeCursor(){ }
    public static String session(String tokenHash){return Digests.sha256(SESSION_NAMESPACE+tokenHash);}
    public static String encode(Position value){
        var node=JSON.createObjectNode();node.put("v",1);node.put("tenant_id",value.tenantId());node.put("audience",value.audience());node.put("session",value.session());if(value.streamId()==null)node.putNull("stream_id");else node.put("stream_id",value.streamId());node.put("sequence",Long.toString(value.sequence()));
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(node));}catch(JsonProcessingException e){throw new IllegalStateException("Cannot encode alarm change coordinate");}
    }
    public static Position decode(String encoded){
        if(encoded==null||encoded.isEmpty()||encoded.length()>MAX_ENCODED_BYTES||!encoded.matches("[A-Za-z0-9_-]+"))throw ApiError.bad("变更游标格式无效");
        try{
            byte[] bytes=Base64.getUrlDecoder().decode(encoded);
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node=JSON.readTree(raw);
            if(node==null||!node.isObject()||node.size()!=6||!node.has("v")||!node.has("tenant_id")||!node.has("audience")||!node.has("session")||!node.has("stream_id")||!node.has("sequence")||!node.get("v").isInt()||node.get("v").intValue()!=1)throw ApiError.bad("变更游标格式无效");
            String tenant=text(node,"tenant_id"),audience=text(node,"audience"),session=text(node,"session"),sequence=text(node,"sequence");
            if(tenant.isEmpty()||tenant.length()>64||!Set.of("management","customer").contains(audience)||!session.matches("[0-9a-f]{64}")||!sequence.matches("0|[1-9][0-9]{0,18}"))throw ApiError.bad("变更游标格式无效");
            String stream=node.get("stream_id").isNull()?null:text(node,"stream_id");
            if(stream!=null&&!stream.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw ApiError.bad("变更游标格式无效");
            long number=Long.parseLong(sequence);if(stream==null&&number!=0)throw ApiError.bad("变更游标格式无效");
            var value=new Position(tenant,audience,session,stream,number);
            if(!encode(value).equals(encoded))throw ApiError.bad("变更游标并非规范编码");return value;
        }catch(ApiError e){throw e;}catch(IllegalArgumentException|java.io.IOException e){throw ApiError.bad("变更游标格式无效");}
    }
    private static String text(JsonNode node,String key){var value=node.get(key);if(value==null||!value.isTextual())throw ApiError.bad("变更游标格式无效");return value.textValue();}
}
