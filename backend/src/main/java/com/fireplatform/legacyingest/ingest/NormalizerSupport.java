package com.fireplatform.legacyingest.ingest;

import com.fireplatform.legacyingest.LegacyNormalizedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class NormalizerSupport {
    private NormalizerSupport() { }
    static String text(JsonNode n,String key){JsonNode v=n==null?null:n.get(key);return v==null||v.isNull()?null:v.isTextual()?v.textValue():v.asText();}
    static BigDecimal decimal(JsonNode n,String key){JsonNode v=n==null?null:n.get(key);if(v==null||v.isNull()||v.asText().isBlank())return null;try{return new BigDecimal(v.asText());}catch(NumberFormatException e){return null;}}
    static boolean hex(String value){return value!=null&&!value.isEmpty()&&value.length()%2==0&&value.matches("[0-9A-Fa-f]+");}
    static String eventKind(String method){if(method==null)return "OTHER";String m=method.toLowerCase(Locale.ROOT);if(m.equals("thing.event.alarmrecovery"))return "RECOVERY";if(m.startsWith("thing.event.heartbeat"))return "HEARTBEAT";if(m.startsWith("thing.event.fault"))return "FAULT";if(m.contains("recover"))return "OTHER";if(m.startsWith("thing.event.alarm")||m.startsWith("thing.event.fire"))return "ALARM";if(m.startsWith("thing.event.offline"))return "OFFLINE";if(m.startsWith("thing.event.param"))return "PARAM";return "OTHER";}
    static String json(ObjectMapper mapper,Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Unable to serialize legacy event parameters",e);}}
    static LegacyNormalizedEvent.LegacyEvent event(ObjectMapper mapper,String kind,String method,Object params,OffsetDateTime time){return new LegacyNormalizedEvent.LegacyEvent(kind,method,json(mapper,params),time);}
    static JsonNode object(JsonNode n){return n!=null&&n.isObject()?n:com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();}
    /**
     * 旧平台时间：兼容 4 种真实格式。
     * 1) ISO-8601 带偏移/Z；2) epoch 秒（10 位）；3) epoch 毫秒（13 位）；
     * 4) "yyyy-MM-dd HH:mm:ss UTC"（自称 UTC）；无时区的本地时间按北京时间（+08:00）解释。
     */
    static OffsetDateTime time(String value){
        if(value==null||value.isBlank())return null;
        String text=value.trim();
        try{return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC);}catch(java.time.format.DateTimeParseException ignored){ }
        if(text.matches("\\d{8}T\\d{6}Z")){
            try{return LocalDateTime.parse(text.substring(0,15),DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")).atOffset(ZoneOffset.UTC);}catch(java.time.format.DateTimeParseException ignored){ }
        }
        if(text.matches("\\d{10}")) return Instant.ofEpochSecond(Long.parseLong(text)).atOffset(ZoneOffset.UTC);
        if(text.matches("\\d{13}")) return Instant.ofEpochMilli(Long.parseLong(text)).atOffset(ZoneOffset.UTC);
        if(text.endsWith(" UTC")){
            try{return LocalDateTime.parse(text.substring(0,text.length()-4).trim().replace(' ','T')).atOffset(ZoneOffset.UTC);}catch(java.time.format.DateTimeParseException ignored){ }
        }
        try{return LocalDateTime.parse(text.replace(' ','T')).atOffset(ZoneOffset.ofHours(8)).withOffsetSameInstant(ZoneOffset.UTC);}catch(java.time.format.DateTimeParseException ignored){ }
        return null;
    }
}
