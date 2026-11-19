package com.fireplatform.notification;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.List;

public final class NotificationInputs {
    private NotificationInputs() { }
    public static class IntegerOnly extends StdDeserializer<Long> { public IntegerOnly(){super(Long.class);} public Long deserialize(JsonParser p,DeserializationContext c)throws IOException { if(p.currentToken()!=JsonToken.VALUE_NUMBER_INT)return (Long)c.handleUnexpectedToken(Long.class,p); return p.getLongValue(); } }
    public static class TextOnly extends StdDeserializer<String> { public TextOnly(){super(String.class);} public String deserialize(JsonParser p,DeserializationContext c)throws IOException { if(p.currentToken()!=JsonToken.VALUE_STRING)return (String)c.handleUnexpectedToken(String.class,p); return p.getText(); } }
    public static class BooleanOnly extends StdDeserializer<Boolean> { public BooleanOnly(){super(Boolean.class);} public Boolean deserialize(JsonParser p,DeserializationContext c)throws IOException { if(p.currentToken()!=JsonToken.VALUE_TRUE && p.currentToken()!=JsonToken.VALUE_FALSE)return (Boolean)c.handleUnexpectedToken(Boolean.class,p); return p.getBooleanValue(); } }
    public record Contact(@JsonDeserialize(using=TextOnly.class)String name,@JsonDeserialize(using=TextOnly.class)String contactType,@JsonDeserialize(using=TextOnly.class)String phone,@JsonDeserialize(using=TextOnly.class)String wechatBindingId,@JsonDeserialize(using=BooleanOnly.class)Boolean enabled,@JsonDeserialize(using=IntegerOnly.class)Long expectedRevision,@JsonDeserialize(using=TextOnly.class)String reason) { }
    public record Receiver(@JsonDeserialize(using=TextOnly.class)String channel,@JsonDeserialize(using=TextOnly.class)String contactId,@JsonDeserialize(using=IntegerOnly.class)Long level) { }
    public record Rule(@JsonDeserialize(using=TextOnly.class)String name,@JsonDeserialize(using=BooleanOnly.class)Boolean enabled,@JsonDeserialize(using=TextOnly.class)String eventScope,List<Receiver> receivers,@JsonDeserialize(using=IntegerOnly.class)Long smsEscalationIntervalMinutes,@JsonDeserialize(using=IntegerOnly.class)Long voiceEscalationIntervalMinutes,@JsonDeserialize(using=IntegerOnly.class)Long wechatEscalationIntervalMinutes,@JsonDeserialize(using=IntegerOnly.class)Long expectedRevision,@JsonDeserialize(using=TextOnly.class)String reason,@JsonDeserialize(using=TextOnly.class)String scopeType,@JsonDeserialize(using=TextOnly.class)String scopeId) {
        /** Internal unit-only rule authors (bulk transfer and rule drafts) have no scoped target. */
        public Rule(String name,Boolean enabled,String eventScope,List<Receiver> receivers,Long smsEscalationIntervalMinutes,
                    Long voiceEscalationIntervalMinutes,Long wechatEscalationIntervalMinutes,Long expectedRevision,String reason) {
            this(name,enabled,eventScope,receivers,smsEscalationIntervalMinutes,voiceEscalationIntervalMinutes,
                wechatEscalationIntervalMinutes,expectedRevision,reason,null,null);
        }
    }
    public record ChannelPolicy(@JsonDeserialize(using=TextOnly.class)String scopeType,@JsonDeserialize(using=TextOnly.class)String placeId,@JsonDeserialize(using=BooleanOnly.class)Boolean smsEnabled,@JsonDeserialize(using=BooleanOnly.class)Boolean voiceEnabled,@JsonDeserialize(using=BooleanOnly.class)Boolean wechatEnabled,@JsonDeserialize(using=IntegerOnly.class)Long expectedRevision,@JsonDeserialize(using=TextOnly.class)String reason) { }
}
