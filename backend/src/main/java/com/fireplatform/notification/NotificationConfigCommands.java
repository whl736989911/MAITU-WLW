package com.fireplatform.notification;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.fireplatform.notification.NotificationReads.*;

@Service
public class NotificationConfigCommands {
    public record Reference(String id) { }
    private final JdbcTemplate jdbc; private final ExecutionAuthorization auth; private final TenantFence tenants; private final ContactPhoneStorage phones; private final NotificationReads reads; private final IdempotencyStore idem; private final DatabaseTime time; private final AuditService audit; private final com.fireplatform.notification.officialaccount.WechatOfficialAccountBindingService wechatBindings;
    public NotificationConfigCommands(JdbcTemplate jdbc,ExecutionAuthorization auth,TenantFence tenants,ContactPhoneStorage phones,NotificationReads reads,IdempotencyStore idem,DatabaseTime time,AuditService audit,com.fireplatform.notification.officialaccount.WechatOfficialAccountBindingService wechatBindings){this.jdbc=jdbc;this.auth=auth;this.tenants=tenants;this.phones=phones;this.reads=reads;this.idem=idem;this.time=time;this.audit=audit;this.wechatBindings=wechatBindings;}
    private void begin(AuthService.Identity actor,String tenant,String key,String reason,List<String> rights){auth.lockRequest(actor,tenant,rights);if(tenant!=null)tenants.lockEnabledRevision(tenant);idem.validateKey(key);NotificationPolicy.text(reason,Limits.NOTIFICATION_REASON_MAX);}
    @Transactional public Map<String,Object> contact(AuthService.Identity actor,String tenant,String id,String key,NotificationInputs.Contact in,String request){
        begin(actor,tenant,key,in.reason(),NotificationPolicy.CONFIG_RIGHTS);phones.requireReady();
        String name=NotificationPolicy.text(in.name(),Limits.NOTIFICATION_NAME_MAX);String type=NotificationPolicy.choice(in.contactType(),NotificationPolicy.TYPES);
        boolean enabled=NotificationPolicy.flag(in.enabled());String normalized=in.phone()==null?null:phones.normalize(in.phone());
        if(id==null&&normalized==null)throw ApiError.bad("创建联系人必须明确填写号码");
        String requestedBinding=in.wechatBindingId();
        String resolvedBinding=requestedBinding==null?null:requestedBinding.trim();
        if(resolvedBinding!=null&&!resolvedBinding.isEmpty())resolvedBinding=wechatBindings.requireUsable(tenant,resolvedBinding).id();
        String hash=idem.fingerprint(Arrays.asList(tenant,id,name,type,enabled,in.expectedRevision(),in.reason(),
            normalized==null?null:phones.fingerprintPhone(tenant,normalized),requestedBinding==null?null:resolvedBinding));
        String op=id==null?"notification-contact-create":"notification-contact-edit";
        var replay=idem.replay(actor.user().id(),op,key,hash,Reference.class);if(replay!=null)return reads.contact(tenant,replay.id());
        if(normalized!=null){var duplicate=jdbc.queryForList("SELECT id FROM notification_contacts WHERE tenant_id=? AND phone_mac=?",String.class,tenant,phones.fingerprintPhone(tenant,normalized));if(duplicate.stream().anyMatch(x->!x.equals(id)))throw ApiError.conflict("CONTACT_PHONE_EXISTS","该单位已有此号码联系人");}
        String target=id==null?UUID.randomUUID().toString():id;var now=time.now();
        if(id==null){
            if(in.expectedRevision()!=null)throw ApiError.bad("创建不接受expected_revision");
            var p=phones.protect(tenant,normalized);
            jdbc.update("INSERT INTO notification_contacts(id,tenant_id,name,contact_type,phone_cipher,phone_nonce,phone_masked,phone_mac,wechat_binding_id,enabled,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                target,tenant,name,type,p.cipher(),p.nonce(),p.masked(),p.mac(),resolvedBinding==null||resolvedBinding.isEmpty()?null:resolvedBinding,enabled,now);
        }else{
            var current=reads.row("notification_contacts",tenant,id,true);NotificationPolicy.revision(in.expectedRevision(),revision(current));
            if(requestedBinding==null){
                jdbc.update("UPDATE notification_contacts SET name=?,contact_type=?,enabled=?,revision=revision+1 WHERE tenant_id=? AND id=?",name,type,enabled,tenant,id);
            }else{
                jdbc.update("UPDATE notification_contacts SET name=?,contact_type=?,enabled=?,wechat_binding_id=?,revision=revision+1 WHERE tenant_id=? AND id=?",
                    name,type,enabled,resolvedBinding==null||resolvedBinding.isEmpty()?null:resolvedBinding,tenant,id);
            }
            if(normalized!=null){var p=phones.protect(tenant,normalized);jdbc.update("UPDATE notification_contacts SET phone_cipher=?,phone_nonce=?,phone_masked=?,phone_mac=? WHERE tenant_id=? AND id=?",p.cipher(),p.nonce(),p.masked(),p.mac(),tenant,id);}
        }
        idem.save(actor.user().id(),op,key,hash,new Reference(target));audit.record(actor.user().id(),tenant,"NOTIFICATION_CONTACT_WRITE",target,"SUCCESS",request);return reads.contact(tenant,target);
    }
    List<Map<String,Object>> lockContacts(String tenant,List<NotificationInputs.Receiver> receivers){var result=new HashMap<String,Map<String,Object>>();receivers.stream().map(NotificationInputs.Receiver::contactId).sorted().forEach(id->{var row=reads.row("notification_contacts",tenant,id,true);if(!enabled(row))throw ApiError.conflict("CONTACT_UNAVAILABLE","规则接收人已停用");result.put(id,row);});for(var receiver:receivers)if("WECHAT".equals(receiver.channel())){Object binding=result.get(receiver.contactId()).get("wechat_binding_id");if(binding==null||String.valueOf(binding).isBlank())throw ApiError.conflict("WECHAT_BINDING_UNAVAILABLE","公众号接收人必须先在联系人中关联已绑定用户");wechatBindings.requireUsable(tenant,String.valueOf(binding));}return receivers.stream().map(r->result.get(r.contactId())).toList();}
    public static void receivers(NotificationInputs.Rule in){
        if(in.receivers()==null||in.receivers().isEmpty()||in.receivers().size()>Limits.NOTIFICATION_MAX_RECEIVERS*3)throw ApiError.bad("规则接收人数无效");
        var keys=new HashSet<String>();var channels=new HashMap<String,TreeSet<Long>>();
        for(String channel:List.of("SMS","VOICE","WECHAT"))channels.put(channel,new TreeSet<>());
        for(var receiver:in.receivers()){if(receiver==null)throw ApiError.bad("规则接收人无效");String channel=NotificationPolicy.choice(receiver.channel(),List.of("SMS","VOICE","WECHAT"));String contact=NotificationPolicy.id(receiver.contactId());if(!keys.add(channel+":"+contact))throw ApiError.bad("同一渠道联系人不能重复");channels.get(channel).add(NotificationPolicy.number(receiver.level(),1,Limits.NOTIFICATION_MAX_LEVEL));}
        for(String channel:List.of("SMS","VOICE")){var levels=channels.get(channel);if(levels.isEmpty()||levels.getFirst()!=1L||levels.size()!=levels.getLast())throw ApiError.bad(channel+"接收等级必须从1开始连续");Long interval=channel.equals("SMS")?in.smsEscalationIntervalMinutes():in.voiceEscalationIntervalMinutes();if(levels.getLast()>1)NotificationPolicy.number(interval,0,60);else if(interval!=null&&interval!=0L)throw ApiError.bad(channel+"单等级规则不能保存无消费者升级间隔");}
        var wechat=channels.get("WECHAT");
        if(!wechat.isEmpty()){if(wechat.getFirst()!=1L||wechat.size()!=wechat.getLast())throw ApiError.bad("WECHAT接收等级必须从1开始连续");Long interval=in.wechatEscalationIntervalMinutes();if(wechat.getLast()>1)NotificationPolicy.number(interval,0,60);else if(interval!=null&&interval!=0L)throw ApiError.bad("WECHAT单等级规则不能保存无消费者升级间隔");}
        else if(in.wechatEscalationIntervalMinutes()!=null&&in.wechatEscalationIntervalMinutes()!=0L)throw ApiError.bad("未配置公众号接收人时不能保存升级间隔");
    }
    @Transactional public Map<String,Object> rule(AuthService.Identity actor,String tenant,String id,String key,NotificationInputs.Rule in,String request){
        begin(actor,tenant,key,in.reason(),NotificationPolicy.CONFIG_RIGHTS);
        String name=NotificationPolicy.text(in.name(),Limits.NOTIFICATION_NAME_MAX);boolean enabled=NotificationPolicy.flag(in.enabled());String eventScope=NotificationPolicy.choice(in.eventScope()==null?"FIRE":in.eventScope(),List.of("FIRE","FAULT"));receivers(in);
        String hash=idem.fingerprint(Arrays.asList(tenant,id,name,enabled,eventScope,in.smsEscalationIntervalMinutes(),in.voiceEscalationIntervalMinutes(),in.wechatEscalationIntervalMinutes(),in.receivers(),in.expectedRevision(),in.reason()));String op=id==null?"notification-rule-create":"notification-rule-edit";var replay=idem.replay(actor.user().id(),op,key,hash,Reference.class);if(replay!=null)return reads.rule(tenant,replay.id());
        if(id!=null){var current=reads.row("notification_rules",tenant,id,true);if(current.get("legacy_mode")!=null)throw ApiError.missing();NotificationPolicy.revision(in.expectedRevision(),revision(current));}else if(in.expectedRevision()!=null)throw ApiError.bad("创建不接受expected_revision");
        lockContacts(tenant,in.receivers());String target=id==null?UUID.randomUUID().toString():id;
        boolean hasWechat=in.receivers().stream().anyMatch(r->"WECHAT".equals(r.channel()));
        Long smsSeconds=in.smsEscalationIntervalMinutes()==null?0L:Math.multiplyExact(in.smsEscalationIntervalMinutes(),60L),voiceSeconds=in.voiceEscalationIntervalMinutes()==null?0L:Math.multiplyExact(in.voiceEscalationIntervalMinutes(),60L);
        Long wechatSeconds=!hasWechat?null:in.wechatEscalationIntervalMinutes()==null?0L:Math.multiplyExact(in.wechatEscalationIntervalMinutes(),60L);
        if(id==null)jdbc.update("INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,escalation_interval_seconds,sms_escalation_interval_seconds,voice_escalation_interval_seconds,wechat_escalation_interval_seconds,created_at) VALUES (?,?,?,?,?,NULL,?,?,?,?)",target,tenant,name,enabled,eventScope,smsSeconds,voiceSeconds,wechatSeconds,time.now());else{jdbc.update("UPDATE notification_rules SET name=?,enabled=?,event_scope=?,escalation_interval_seconds=NULL,sms_escalation_interval_seconds=?,voice_escalation_interval_seconds=?,wechat_escalation_interval_seconds=?,revision=revision+1 WHERE id=? AND tenant_id=? AND legacy_mode IS NULL",name,enabled,eventScope,smsSeconds,voiceSeconds,wechatSeconds,id,tenant);jdbc.update("DELETE FROM notification_rule_receivers WHERE rule_id=? AND tenant_id=?",id,tenant);}
        for(var r:in.receivers())jdbc.update("INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel) VALUES (?,?,?,?,?)",tenant,target,r.contactId(),r.level(),r.channel());
        idem.save(actor.user().id(),op,key,hash,new Reference(target));audit.record(actor.user().id(),tenant,"NOTIFICATION_RULE_WRITE",target,"SUCCESS",request);return reads.rule(tenant,target);
    }
}
