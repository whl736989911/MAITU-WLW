package com.fireplatform.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import com.fireplatform.transport.AliyunNotificationClient;
import com.fireplatform.transport.NotificationEndpoint;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationProviderService {
    public record View(boolean smsEnabled, boolean voiceEnabled, String smsSignName, String smsTemplateCode,
                       String ttsCode, String ttsCalledShowNumber, String smsEndpoint, String voiceEndpoint,
                       Map<String,String> smsTemplateVariables, Map<String,String> voiceTemplateVariables,
                       String accessKeyIdMasked, boolean secretConfigured, boolean testModeEnabled, String testPhoneMasked, long revision, OffsetDateTime updatedAt) { }
    public record TestResult(boolean success, String channel, String providerId, String code, String message, AliyunNotificationClient.Result.Outcome outcome) { }
    public record Status(boolean realEnabled, boolean smsReady, boolean voiceReady, boolean secretStorageReady, boolean smsEnabled, boolean voiceEnabled, boolean testModeEnabled, String testPhoneMasked, long revision) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VARIABLE_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    private static final Set<String> EVENT_FIELDS = Set.of("device_name", "address", "alarmTime", "event");
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final AuditService audit;
    private final NotificationProviderSecretStorage secrets;
    private final AliyunNotificationClient client;
    private final ContactPhoneStorage phones;
    private final boolean realEnabled;
    private long appliedRevision = -1;
    public NotificationProviderService(JdbcTemplate jdbc,ManagementGuard guard,AuditService audit,
            NotificationProviderSecretStorage secrets,AliyunNotificationClient client,ContactPhoneStorage phones,
            @org.springframework.beans.factory.annotation.Value("${fire.notification.real-enabled:false}") boolean realEnabled) {
        this.jdbc=jdbc; this.guard=guard; this.audit=audit; this.secrets=secrets; this.client=client; this.phones=phones; this.realEnabled=realEnabled;
    }

    @PostConstruct
    public void loadConfiguredProvider() {
        try { var saved=row(); publish(saved.revision(),runtimeConfig(saved)); }
        catch (RuntimeException error) {
            // Unreadable persisted configuration must not leave environment senders enabled.
            client.apply(new AliyunNotificationClient.Config(null,null,null,null,null,null,null,null,null,null));
        }
    }

    public View get() { return view(row()); }
    public Status status() { var current=row(); return new Status(realEnabled,client.enabled(),client.voiceReady(),secrets.ready(),current.smsEnabled(),current.voiceEnabled(),current.testModeEnabled(),current.testPhoneMasked(),current.revision()); }

    @Transactional
    public View update(AuthService.Identity actor, NotificationProviderInputs.Provider input, String request) {
        guard.begin(actor,"notification-config:write",null,"management");
        var current=row(true);
        if(input.expectedRevision()==null || input.expectedRevision()!=current.revision) throw ApiError.conflict("REVISION_CONFLICT","通知通道配置已更新，请刷新后重试");
        boolean sms=NotificationPolicy.flag(input.smsEnabled()), voice=NotificationPolicy.flag(input.voiceEnabled());
        boolean testMode=NotificationPolicy.flag(input.testModeEnabled());
        String sign=optional(input.smsSignName(),200), smsCode=optional(input.smsTemplateCode(),100), tts=optional(input.ttsCode(),100), called=optional(input.ttsCalledShowNumber(),50);
        var smsVariables = resolveVariables("短信", sms, smsCode, current.smsCode(), current.smsVariables(),
            input.smsTemplateVariables(), input.smsTemplateVariablesConfirmed());
        var voiceVariables = resolveVariables("语音", voice, tts, current.tts(), current.voiceVariables(),
            input.voiceTemplateVariables(), input.voiceTemplateVariablesConfirmed());
        String smsEndpoint=endpoint(input.smsEndpoint(),"https://dysmsapi.aliyuncs.com/"), voiceEndpoint=endpoint(input.voiceEndpoint(),"https://dyvmsapi.aliyuncs.com/");
        String accessKeyId=input.accessKeyId()==null||input.accessKeyId().isBlank()?current.accessKeyId():NotificationPolicy.text(input.accessKeyId(),200).trim();
        String cipher=current.secretCipher(), nonce=current.secretNonce(), masked=current.secretMasked();
        if(input.accessKeySecret()!=null && !input.accessKeySecret().isBlank()) {
            if(!secrets.ready()) throw new ApiError(503,"PROVIDER_SECRET_STORAGE_UNAVAILABLE","通知通道密钥存储未配置，不能保存 SecretKey");
            var protectedSecret=secrets.protect(input.accessKeySecret().trim());
            cipher=protectedSecret.cipher(); nonce=protectedSecret.nonce(); masked=protectedSecret.masked();
        }
        String testCipher=current.testPhoneCipher(), testNonce=current.testPhoneNonce(), testMasked=current.testPhoneMasked();
        if(input.testPhone()!=null && !input.testPhone().isBlank()) {
            if(!secrets.ready()) throw new ApiError(503,"PROVIDER_SECRET_STORAGE_UNAVAILABLE","通知通道密钥存储未配置，不能保存测试号码");
            String normalized=phones.normalize(input.testPhone());
            var protectedPhone=secrets.protect(normalized);
            testCipher=protectedPhone.cipher(); testNonce=protectedPhone.nonce(); testMasked=protectedPhone.masked();
        }
        if(testMode && (testCipher==null || testNonce==null)) throw ApiError.bad("启用测试接收号码前必须先配置手机号");
        jdbc.update("""
            UPDATE notification_provider_config SET sms_enabled=?,voice_enabled=?,sms_sign_name=?,sms_template_code=?,
              tts_code=?,tts_called_show_number=?,sms_template_variables=?,voice_template_variables=?,sms_endpoint=?,voice_endpoint=?,
              access_key_id=?,access_key_secret_cipher=?,access_key_secret_nonce=?,access_key_secret_masked=?,
              test_mode_enabled=?,test_phone_cipher=?,test_phone_nonce=?,test_phone_masked=?,
              revision=revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=? WHERE id=1
            """, sms,voice,blank(sign),blank(smsCode),blank(tts),blank(called),encodeVariables(smsVariables),encodeVariables(voiceVariables),
            smsEndpoint,voiceEndpoint,blank(accessKeyId),cipher,nonce,masked,testMode,testCipher,testNonce,testMasked,actor.user().id());
        var updated=row();
        var config=runtimeConfig(updated);
        audit.record(actor.user().id(),null,"NOTIFICATION_PROVIDER_UPDATE","notification-provider","SUCCESS",request);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publish(updated.revision(),config); }
        });
        return view(updated);
    }

    @Transactional
    public TestResult test(AuthService.Identity actor, NotificationProviderInputs.ProviderTest input, String request) {
        guard.begin(actor,"notification-config:write",null,"management");
        String channel=NotificationPolicy.choice(input.channel(),List.of("SMS","VOICE"));
        String phone=phones.normalize(input.phone());
        var current=row();
        if(channel.equals("SMS") && !current.smsEnabled()) throw ApiError.conflict("PROVIDER_DISABLED","短信通道未启用");
        if(channel.equals("VOICE") && !current.voiceEnabled()) throw ApiError.conflict("VOICE_DISABLED","语音通道未启用");
        if(channel.equals("SMS") && !client.enabled()) throw ApiError.conflict("PROVIDER_NOT_CONFIGURED","阿里云短信凭据、模板或变量映射未配置");
        if(channel.equals("VOICE") && !client.voiceReady()) throw ApiError.conflict("VOICE_NOT_CONFIGURED","阿里云语音凭据、模板或变量映射未配置");
        String testTime=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        Map<String,String> params=Map.of("device_name","测试设备","address","测试位置","alarmTime",testTime,"event","火灾报警");
        var result=channel.equals("SMS")?client.sendSms(phone,params,null):client.sendVoice(phone,params,null);
        audit.record(actor.user().id(),null,"NOTIFICATION_PROVIDER_TEST","notification-provider",result.unknown()?"UNKNOWN":result.success()?"SUCCESS":"FAILED",request);
        return new TestResult(result.success(),channel,result.providerId(),result.code(),result.message(),result.outcome());
    }

    private record Row(boolean smsEnabled,boolean voiceEnabled,String sign,String smsCode,String tts,String called,
                       Map<String,String> smsVariables,Map<String,String> voiceVariables,String smsEndpoint,String voiceEndpoint,
                       String accessKeyId,String secretCipher,String secretNonce,String secretMasked,boolean testModeEnabled,
                       String testPhoneCipher,String testPhoneNonce,String testPhoneMasked,long revision,OffsetDateTime updatedAt) { }
    private Row row() { return row(false); }
    private Row row(boolean lock) {
        var rows=jdbc.query("SELECT * FROM notification_provider_config WHERE id=1"+(lock?" FOR UPDATE":""),
            (rs,n)->new Row(rs.getBoolean("sms_enabled"),rs.getBoolean("voice_enabled"),rs.getString("sms_sign_name"),
                rs.getString("sms_template_code"),rs.getString("tts_code"),rs.getString("tts_called_show_number"),
                decodeVariables(rs.getString("sms_template_variables")),decodeVariables(rs.getString("voice_template_variables")),
                rs.getString("sms_endpoint"),rs.getString("voice_endpoint"),rs.getString("access_key_id"),
                rs.getString("access_key_secret_cipher"),rs.getString("access_key_secret_nonce"),rs.getString("access_key_secret_masked"),
                rs.getBoolean("test_mode_enabled"),rs.getString("test_phone_cipher"),rs.getString("test_phone_nonce"),rs.getString("test_phone_masked"),
                rs.getLong("revision"),rs.getObject("updated_at",OffsetDateTime.class)));
        if(rows.isEmpty()) throw ApiError.conflict("PROVIDER_CONFIG_MISSING","通知通道配置缺失"); return rows.getFirst();
    }
    private View view(Row row) {
        return new View(row.smsEnabled(),row.voiceEnabled(),row.sign(),row.smsCode(),row.tts(),row.called(),row.smsEndpoint(),row.voiceEndpoint(),
            row.smsVariables(),row.voiceVariables(),mask(row.accessKeyId()),row.secretCipher()!=null&&row.secretNonce()!=null,
            row.testModeEnabled(),row.testPhoneMasked(),row.revision(),row.updatedAt());
    }
    /** Real-dispatch settings stay internal; never expose the plain test number through an API. */
    public record DispatchSettings(boolean smsEnabled,boolean voiceEnabled,String testRecipient) { }
    public DispatchSettings dispatchSettings() {
        var row=row();
        String recipient=null;
        if(row.testModeEnabled() && row.testPhoneCipher()!=null && row.testPhoneNonce()!=null) {
            if(!secrets.ready()) throw new ApiError(503,"PROVIDER_SECRET_STORAGE_UNAVAILABLE","测试接收号码密钥存储未配置");
            recipient=secrets.reveal(row.testPhoneCipher(),row.testPhoneNonce());
        }
        return new DispatchSettings(row.smsEnabled(),row.voiceEnabled(),recipient);
    }
    private synchronized void publish(long revision, AliyunNotificationClient.Config config) {
        if (revision <= appliedRevision) return;
        client.apply(config);
        appliedRevision = revision;
    }
    private AliyunNotificationClient.Config runtimeConfig(Row row) {
        String secret=null;
        if(row.secretCipher()!=null && row.secretNonce()!=null && secrets.ready()) secret=secrets.reveal(row.secretCipher(),row.secretNonce());
        // Disable only the runtime send template; retain stored templates and credentials for receipt queries.
        return new AliyunNotificationClient.Config(row.accessKeyId(),secret,row.sign(),row.smsEnabled()?row.smsCode():null,
            row.voiceEnabled()?row.tts():null,row.called(),row.smsEndpoint(),row.voiceEndpoint(),row.smsVariables(),row.voiceVariables());
    }

    private static Map<String,String> resolveVariables(String channel, boolean enabled, String templateId, String currentTemplateId,
            Map<String,String> currentVariables, JsonNode submitted, Boolean confirmed) {
        boolean templateChanged = !Objects.equals(templateId, currentTemplateId);
        // Omission preserves a mapping only for the same ID; explicit JSON null clears it.
        var mapping = submitted == null && !templateChanged ? currentVariables : parseVariables(submitted);
        if (mapping != null && (templateChanged || currentVariables == null) && !Boolean.TRUE.equals(confirmed))
            throw ApiError.bad("请先核对并确认" + channel + "模板 ID 与变量映射");
        if (enabled && mapping == null)
            throw ApiError.bad("启用" + channel + "前必须核对并填写" + channel + "模板变量映射；无变量请填写空对象");
        return mapping;
    }

    private static Map<String,String> parseVariables(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject() || node.size()>16) throw ApiError.bad("模板变量映射必须是最多 16 组的 JSON 对象");
        Map<String,String> fields = new TreeMap<>();
        for (var entry : node.properties()) {
            JsonNode value = entry.getValue();
            if (!VARIABLE_NAME.matcher(entry.getKey()).matches() || !value.isTextual() || !EVENT_FIELDS.contains(value.textValue()))
                throw ApiError.bad("模板变量名或事件字段无效；可用字段：device_name、address、alarmTime、event");
            fields.put(entry.getKey(), value.textValue());
        }
        return fields;
    }
    private static String encodeVariables(Map<String,String> variables) {
        return variables == null ? null : JSON.valueToTree(variables).toString();
    }
    private static Map<String,String> decodeVariables(String variables) {
        if (variables == null) return null;
        try { return parseVariables(JSON.readTree(variables)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Persisted template mapping is not JSON", e);
        }
    }
    private static String optional(String value,int max) { return value==null||value.isBlank()?null:NotificationPolicy.text(value,max).trim(); }
    private static String endpoint(String value,String fallback) {
        try { return NotificationEndpoint.httpsOrDefault(value,fallback); }
        catch (IllegalArgumentException invalid) { throw ApiError.bad("通知通道地址必须为有效 HTTPS URL"); }
    }
    private static String blank(String value) { return value==null||value.isBlank()?null:value; }
    private static String mask(String value) { if(value==null||value.isBlank())return null; return value.length()<=8?"****":"****"+value.substring(value.length()-4); }
}
