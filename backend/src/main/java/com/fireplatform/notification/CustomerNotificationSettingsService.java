package com.fireplatform.notification;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer unit-admin self-service; this deliberately cannot touch provider or global delivery gates. */
@Service
public class CustomerNotificationSettingsService {
    private final JdbcTemplate jdbc; private final AuthService auth; private final TenantFence tenants; private final DeviceScopes scopes; private final ContactPhoneStorage phones; private final NotificationReads reads; private final IdempotencyStore idem; private final DatabaseTime time; private final AuditService audit; private final com.fireplatform.notification.officialaccount.WechatOfficialAccountBindingService wechatBindings; private final com.fireplatform.notification.officialaccount.WechatOfficialAccountConfigService wechatConfig;
    public CustomerNotificationSettingsService(JdbcTemplate jdbc,AuthService auth,TenantFence tenants,DeviceScopes scopes,ContactPhoneStorage phones,NotificationReads reads,IdempotencyStore idem,DatabaseTime time,AuditService audit,com.fireplatform.notification.officialaccount.WechatOfficialAccountBindingService wechatBindings,com.fireplatform.notification.officialaccount.WechatOfficialAccountConfigService wechatConfig){this.jdbc=jdbc;this.auth=auth;this.tenants=tenants;this.scopes=scopes;this.phones=phones;this.reads=reads;this.idem=idem;this.time=time;this.audit=audit;this.wechatBindings=wechatBindings;this.wechatConfig=wechatConfig;}
    @Transactional public Page<Map<String,Object>> rules(AuthService.Identity actor,int page,Integer size,String keyword,String scopeType,String scopeId){String tenant=tenant(actor);auth.lockAndAuthorize(actor,"unit-notifications:read",tenant,"customer");if(!scopes.all(actor.user().id(),tenant))throw ApiError.forbidden("单位通知配置要求覆盖本单位全部设备范围");return reads.rules(actor.user().id(),tenant,ScopeFilter.Selection.all(),page,size,keyword,null,scopeType,scopeId);}
    private String tenant(AuthService.Identity actor){if(!"customer".equals(actor.audience())||!"CUSTOMER".equals(actor.user().userType())||actor.user().tenantId()==null)throw ApiError.forbidden("仅本单位客户管理员可配置通知");return actor.user().tenantId();}
    private String write(AuthService.Identity actor){String tenant=tenant(actor);auth.lockAndAuthorize(actor,"unit-notifications:write",tenant,"customer");if(!scopes.all(actor.user().id(),tenant))throw ApiError.forbidden("单位通知配置要求覆盖本单位全部设备范围");tenants.lockEnabledRevision(tenant);return tenant;}
    @Transactional public Map<String,Object> settings(AuthService.Identity actor){String tenant=tenant(actor);auth.lockAndAuthorize(actor,"unit-notifications:read",tenant,"customer");if(!scopes.all(actor.user().id(),tenant))throw ApiError.forbidden("单位通知配置要求覆盖本单位全部设备范围");return Map.of("tenant_id",tenant);}
    @Transactional public Page<Map<String,Object>> contacts(AuthService.Identity actor,int page,Integer size,String keyword){String tenant=tenant(actor);auth.lockAndAuthorize(actor,"unit-notifications:read",tenant,"customer");if(!scopes.all(actor.user().id(),tenant))throw ApiError.forbidden("单位通知配置要求覆盖本单位全部设备范围");return reads.contacts(tenant,page,size,keyword,null);}
    @Transactional public Map<String,Object> rule(AuthService.Identity actor,String id){String tenant=tenant(actor);auth.lockAndAuthorize(actor,"unit-notifications:read",tenant,"customer");if(!scopes.all(actor.user().id(),tenant))throw ApiError.forbidden("单位通知配置要求覆盖本单位全部设备范围");return reads.rule(tenant,id);}
    @Transactional public Map<String,Object> effectiveRule(AuthService.Identity actor,String deviceId,String eventScope){
        String tenant=tenant(actor);
        auth.lockAndAuthorize(actor,"unit-notifications:read",tenant,"customer");
        scopes.requireAll(actor.user().id(),tenant);
        return reads.effective(actor.user().id(),tenant,deviceId,eventScope);
    }
    @Transactional public Map<String,Object> contact(AuthService.Identity actor,String id,String key,NotificationInputs.Contact in,String request){
        String tenant=write(actor);idem.validateKey(key);phones.requireReady();
        String name=NotificationPolicy.text(in.name(),Limits.NOTIFICATION_NAME_MAX),type=NotificationPolicy.choice(in.contactType(),NotificationPolicy.TYPES);
        boolean enabled=NotificationPolicy.flag(in.enabled());String normalized=in.phone()==null?null:phones.normalize(in.phone());
        if(id==null&&normalized==null)throw ApiError.bad("创建联系人必须填写号码");
        String requestedBinding=in.wechatBindingId();String resolvedBinding=requestedBinding==null?null:requestedBinding.trim();
        if(resolvedBinding!=null&&!resolvedBinding.isEmpty())resolvedBinding=wechatBindings.requireUsable(tenant,resolvedBinding).id();
        String reason=NotificationPolicy.text(in.reason(),Limits.NOTIFICATION_REASON_MAX);
        String hash=idem.fingerprint(Arrays.asList(tenant,id,name,type,enabled,in.expectedRevision(),reason,
            normalized==null?null:phones.fingerprintPhone(tenant,normalized),requestedBinding==null?null:resolvedBinding));
        String operation=id==null?"unit-notify-contact-create":"unit-notify-contact-edit";
        var replay=idem.replay(actor.user().id(),operation,key,hash,NotificationConfigCommands.Reference.class);if(replay!=null)return reads.contact(tenant,replay.id());
        String target=id==null?UUID.randomUUID().toString():id;
        if(normalized!=null){String mac=phones.fingerprintPhone(tenant,normalized);var duplicates=jdbc.queryForList("SELECT id FROM notification_contacts WHERE tenant_id=? AND phone_mac=?",String.class,tenant,mac);if(duplicates.stream().anyMatch(existing->!existing.equals(id)))throw ApiError.conflict("CONTACT_PHONE_EXISTS","本单位已有此号码联系人");}
        if(id==null){
            if(in.expectedRevision()!=null)throw ApiError.bad("创建不接受expected_revision");
            var protectedPhone=phones.protect(tenant,normalized);
            jdbc.update("INSERT INTO notification_contacts(id,tenant_id,name,contact_type,phone_cipher,phone_nonce,phone_masked,phone_mac,wechat_binding_id,enabled,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                target,tenant,name,type,protectedPhone.cipher(),protectedPhone.nonce(),protectedPhone.masked(),protectedPhone.mac(),
                resolvedBinding==null||resolvedBinding.isEmpty()?null:resolvedBinding,enabled,time.now());
        }else{
            var current=reads.row("notification_contacts",tenant,id,true);NotificationPolicy.revision(in.expectedRevision(),NotificationReads.revision(current));
            if(requestedBinding==null){
                if(normalized==null)jdbc.update("UPDATE notification_contacts SET name=?,contact_type=?,enabled=?,revision=revision+1 WHERE tenant_id=? AND id=?",name,type,enabled,tenant,id);
                else{var protectedPhone=phones.protect(tenant,normalized);jdbc.update("UPDATE notification_contacts SET name=?,contact_type=?,enabled=?,phone_cipher=?,phone_nonce=?,phone_masked=?,phone_mac=?,revision=revision+1 WHERE tenant_id=? AND id=?",name,type,enabled,protectedPhone.cipher(),protectedPhone.nonce(),protectedPhone.masked(),protectedPhone.mac(),tenant,id);}
            }else{
                String binding=resolvedBinding==null||resolvedBinding.isEmpty()?null:resolvedBinding;
                if(normalized==null)jdbc.update("UPDATE notification_contacts SET name=?,contact_type=?,enabled=?,wechat_binding_id=?,revision=revision+1 WHERE tenant_id=? AND id=?",name,type,enabled,binding,tenant,id);
                else{var protectedPhone=phones.protect(tenant,normalized);jdbc.update("UPDATE notification_contacts SET name=?,contact_type=?,enabled=?,wechat_binding_id=?,phone_cipher=?,phone_nonce=?,phone_masked=?,phone_mac=?,revision=revision+1 WHERE tenant_id=? AND id=?",name,type,enabled,binding,protectedPhone.cipher(),protectedPhone.nonce(),protectedPhone.masked(),protectedPhone.mac(),tenant,id);}
            }
        }
        idem.save(actor.user().id(),operation,key,hash,new NotificationConfigCommands.Reference(target));audit.record(actor.user().id(),tenant,"CUSTOMER_NOTIFICATION_CONTACT_WRITE",target,"SUCCESS",request);return reads.contact(tenant,target);
    }
    @Transactional public java.util.List<com.fireplatform.notification.officialaccount.WechatOfficialAccountBindingService.RecipientView> wechatRecipients(AuthService.Identity actor){String tenant=tenant(actor);auth.lockAndAuthorize(actor,"unit-notifications:read",tenant,"customer");if(!scopes.all(actor.user().id(),tenant))throw ApiError.forbidden("单位通知配置要求覆盖本单位全部设备范围");return wechatBindings.recipients(tenant);}
    @Transactional public Map<String,Object> wechatChannel(AuthService.Identity actor){String tenant=tenant(actor);auth.lockAndAuthorize(actor,"unit-notifications:read",tenant,"customer");if(!scopes.all(actor.user().id(),tenant))throw ApiError.forbidden("单位通知配置要求覆盖本单位全部设备范围");var recipients=wechatBindings.recipients(tenant);var config=wechatConfig.view();var out=new LinkedHashMap<String,Object>();out.put("configured",config.configured());out.put("enabled",config.enabled());out.put("ready",config.enabled()&&config.oauthReady()&&(config.fireTemplateReady()||config.faultTemplateReady()));out.put("fire_template_ready",config.fireTemplateReady());out.put("fault_template_ready",config.faultTemplateReady());out.put("active_recipients",recipients.size());out.put("message",!config.configured()?"平台尚未配置公众号":!config.enabled()?"公众号通道未启用":!(config.fireTemplateReady()||config.faultTemplateReady())?"公众号模板未配置完整":"公众号通道可用；发送仍受单位通道策略和通知规则约束");return out;}
    @Transactional public Map<String,Object> rule(AuthService.Identity actor,String id,String key,NotificationInputs.Rule in,String request){
        String tenant=write(actor);idem.validateKey(key);
        if(in.eventScope()==null)throw ApiError.bad("必须明确指定FIRE或FAULT事件类型");
        String event=NotificationPolicy.choice(in.eventScope(),List.of("FIRE","FAULT"));String name=NotificationPolicy.text(in.name(),Limits.NOTIFICATION_NAME_MAX);boolean enabled=NotificationPolicy.flag(in.enabled());NotificationConfigCommands.receivers(in);
        String reason=NotificationPolicy.text(in.reason(),Limits.NOTIFICATION_REASON_MAX);
        var owner=NotificationRuleTarget.requested(tenant,in.scopeType(),in.scopeId());
        var current=id==null?null:reads.row("notification_rules",tenant,id,true);
        if(current!=null){
            if(current.get("legacy_mode")!=null)throw ApiError.missing();
            NotificationRuleTarget.fromRule(tenant,current).lockAuthorized(jdbc,scopes,actor.user().id(),tenant);
            owner.requireUnchanged(tenant,current);
        }else owner.lockAuthorized(jdbc,scopes,actor.user().id(),tenant);
        var fields=new ArrayList<Object>(Arrays.asList(tenant,id,event,name,enabled,in.receivers(),in.smsEscalationIntervalMinutes(),in.voiceEscalationIntervalMinutes(),in.wechatEscalationIntervalMinutes(),in.expectedRevision(),reason));
        owner.addToFingerprint(fields);
        String hash=idem.fingerprint(fields);
        String operation=id==null?"unit-notify-rule-create":"unit-notify-rule-edit";var replay=idem.replay(actor.user().id(),operation,key,hash,NotificationConfigCommands.Reference.class);if(replay!=null)return reads.rule(tenant,replay.id());
        if(current!=null)NotificationPolicy.revision(in.expectedRevision(),NotificationReads.revision(current));else if(in.expectedRevision()!=null)throw ApiError.bad("创建不接受expected_revision");
        in.receivers().stream().map(NotificationInputs.Receiver::contactId).distinct().sorted().forEach(contactId->{
            var contact=reads.row("notification_contacts",tenant,contactId,true);
            if(!NotificationReads.enabled(contact))throw ApiError.conflict("CONTACT_UNAVAILABLE","规则接收联系人已停用");
        });
        for(var receiver:in.receivers())if("WECHAT".equals(receiver.channel())){
            Object binding=reads.row("notification_contacts",tenant,receiver.contactId(),true).get("wechat_binding_id");
            if(binding==null||String.valueOf(binding).isBlank())throw ApiError.conflict("WECHAT_BINDING_UNAVAILABLE","公众号接收人必须先在联系人中关联已绑定用户");
            wechatBindings.requireUsable(tenant,String.valueOf(binding));
        }
        String target=id==null?UUID.randomUUID().toString():id;
        boolean hasWechat=in.receivers().stream().anyMatch(r->"WECHAT".equals(r.channel()));
        Long sms=in.smsEscalationIntervalMinutes()==null?0L:Math.multiplyExact(in.smsEscalationIntervalMinutes(),60L),voice=in.voiceEscalationIntervalMinutes()==null?0L:Math.multiplyExact(in.voiceEscalationIntervalMinutes(),60L);
        Long wechat=!hasWechat?null:in.wechatEscalationIntervalMinutes()==null?0L:Math.multiplyExact(in.wechatEscalationIntervalMinutes(),60L);
        if(id==null)jdbc.update("INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,escalation_interval_seconds,sms_escalation_interval_seconds,voice_escalation_interval_seconds,wechat_escalation_interval_seconds,created_at,scope_type,scope_place_id,scope_device_id) VALUES (?,?,?,?,?,NULL,?,?,?,?,?,?,?)",target,tenant,name,enabled,event,sms,voice,wechat,time.now(),owner.type(),owner.placeId(),owner.deviceId());
        else{
            jdbc.update("UPDATE notification_rules SET name=?,enabled=?,event_scope=?,escalation_interval_seconds=NULL,sms_escalation_interval_seconds=?,voice_escalation_interval_seconds=?,wechat_escalation_interval_seconds=?,revision=revision+1 WHERE id=? AND tenant_id=? AND legacy_mode IS NULL",name,enabled,event,sms,voice,wechat,id,tenant);
            jdbc.update("DELETE FROM notification_rule_receivers WHERE tenant_id=? AND rule_id=?",tenant,id);
        }
        for(var receiver:in.receivers())jdbc.update("INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel) VALUES (?,?,?,?,?)",tenant,target,receiver.contactId(),receiver.level(),receiver.channel());
        idem.save(actor.user().id(),operation,key,hash,new NotificationConfigCommands.Reference(target));audit.record(actor.user().id(),tenant,"CUSTOMER_NOTIFICATION_RULE_WRITE",target,"SUCCESS",request);return reads.rule(tenant,target);
    }
}
