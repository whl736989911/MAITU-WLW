package com.fireplatform.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fireplatform.notification.NotificationInputs.*;

public final class NotificationProviderInputs {
    private NotificationProviderInputs() { }
    public record Provider(@JsonDeserialize(using=BooleanOnly.class) Boolean smsEnabled,
                           @JsonDeserialize(using=BooleanOnly.class) Boolean voiceEnabled,
                           @JsonDeserialize(using=TextOnly.class) String smsSignName,
                           @JsonDeserialize(using=TextOnly.class) String smsTemplateCode,
                           @JsonDeserialize(using=TextOnly.class) String ttsCode,
                           @JsonDeserialize(using=TextOnly.class) String ttsCalledShowNumber,
                           JsonNode smsTemplateVariables,
                           JsonNode voiceTemplateVariables,
                           @JsonDeserialize(using=TextOnly.class) String smsEndpoint,
                           @JsonDeserialize(using=TextOnly.class) String voiceEndpoint,
                           @JsonDeserialize(using=TextOnly.class) String accessKeyId,
                           @JsonDeserialize(using=TextOnly.class) String accessKeySecret,
                           @JsonDeserialize(using=BooleanOnly.class) Boolean testModeEnabled,
                           @JsonDeserialize(using=TextOnly.class) String testPhone,
                           @JsonDeserialize(using=IntegerOnly.class) Long expectedRevision) { }
    public record ProviderTest(@JsonDeserialize(using=TextOnly.class) String channel,
                               @JsonDeserialize(using=TextOnly.class) String phone) { }
}
