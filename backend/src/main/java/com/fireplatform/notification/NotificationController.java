package com.fireplatform.notification;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationController {
    private final RequestSecurity security; private final ContactPhoneStorage phones; private final NotificationReads reads; private final NotificationConfigCommands configurations; private final RealNotificationService real; private final NotificationChannelPolicyService channelPolicies; private final NotificationProviderService provider;
    public NotificationController(RequestSecurity security,ContactPhoneStorage phones,NotificationReads reads,NotificationConfigCommands configurations,RealNotificationService real,NotificationChannelPolicyService channelPolicies,NotificationProviderService provider){this.security=security;this.phones=phones;this.reads=reads;this.configurations=configurations;this.real=real;this.channelPolicies=channelPolicies;this.provider=provider;}
    private AuthService.Identity require(HttpServletRequest r,String... rights){AuthService.Identity actor=null;for(String right:rights)actor=security.require(r,right,"management");return actor;}
    private String tenant(HttpServletRequest r){return security.tenant(r);}
    private void global(HttpServletRequest r){if(r.getHeader("X-Tenant-Id")!=null)throw ApiError.bad("此全局通知接口不接受单位上下文");}
    private String request(HttpServletRequest r){return RequestContext.requestId(r);}
    private Boolean enabled(String value){if(value==null||value.isEmpty())return null;if(value.equals("true"))return true;if(value.equals("false"))return false;throw ApiError.bad("enabled须为true或false");}
    @GetMapping("/api/management/notification-real") public RealNotificationService.Stats real(HttpServletRequest r){require(r,"notifications:read");global(r);return real.stats();}
    @GetMapping("/api/management/notification-capabilities")
    public Map<String,Object> capabilities(HttpServletRequest r){
        global(r);var actor=security.identity(r);
        if(!actor.audience().equals("management")||actor.permissions().stream().noneMatch(p->List.of("notification-config:read","notifications:read").contains(p)))throw ApiError.forbidden("没有通知读取权限");
        var status=provider.status();
        boolean smsReady=status.realEnabled()&&status.smsReady(),voiceReady=status.realEnabled()&&status.voiceReady();
        return Map.of("mode","REAL_PROVIDER","provider_ready",smsReady||voiceReady,"sms_ready",smsReady,"voice_ready",voiceReady,"contact_storage_ready",phones.contactStorageReady());
    }
    @GetMapping("/api/management/notification-contacts") public Page<Map<String,Object>> contacts(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,@RequestParam(required=false)String keyword,@RequestParam(required=false)String enabled,HttpServletRequest r){var actor=require(r,"notification-config:read");return reads.contacts(actor.user().id(),security.optionalTenant(r),security.readSelection(r),page,size,keyword,enabled(enabled));}
    @GetMapping("/api/management/notification-contacts/{id}") public Map<String,Object> contact(@PathVariable String id,HttpServletRequest r){require(r,"notification-config:read");return reads.contact(tenant(r),id);}
    @PostMapping("/api/management/notification-contacts") public ResponseEntity<Map<String,Object>> contactCreate(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationInputs.Contact body,HttpServletRequest r){var actor=require(r,"notification-config:read","notification-config:write");return ResponseEntity.status(201).body(configurations.contact(actor,tenant(r),null,key,body,request(r)));}
    @PutMapping("/api/management/notification-contacts/{id}") public Map<String,Object> contactEdit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationInputs.Contact body,HttpServletRequest r){var actor=require(r,"notification-config:read","notification-config:write");return configurations.contact(actor,tenant(r),id,key,body,request(r));}
    @GetMapping("/api/management/notification-channel-policy") public Map<String,Object> channelPolicy(@RequestParam(defaultValue="UNIT")String scopeType,@RequestParam(required=false)String placeId,HttpServletRequest r){var actor=require(r,"notification-config:read");return channelPolicies.read(actor.user().id(),tenant(r),scopeType,placeId,security.readSelection(r));}
    @PutMapping("/api/management/notification-channel-policy") public Map<String,Object> updateChannelPolicy(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationInputs.ChannelPolicy body,HttpServletRequest r){var actor=require(r,"notification-config:read","notification-config:write");return channelPolicies.put(actor,tenant(r),key,body,request(r),security.readSelection(r));}
    @GetMapping("/api/management/notification-rules") public Page<Map<String,Object>> rules(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,@RequestParam(required=false)String keyword,@RequestParam(required=false)String enabled,HttpServletRequest r){var actor=require(r,"notification-config:read");return reads.rules(actor.user().id(),security.optionalTenant(r),security.readSelection(r),page,size,keyword,enabled(enabled));}
    @GetMapping("/api/management/notification-rules/{id}") public Map<String,Object> rule(@PathVariable String id,HttpServletRequest r){require(r,"notification-config:read");return reads.rule(tenant(r),id);}
    @PostMapping("/api/management/notification-rules") public ResponseEntity<Map<String,Object>> ruleCreate(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationInputs.Rule body,HttpServletRequest r){var actor=require(r,"notification-config:read","notification-config:write");return ResponseEntity.status(201).body(configurations.rule(actor,tenant(r),null,key,body,request(r)));}
    @PutMapping("/api/management/notification-rules/{id}") public Map<String,Object> ruleEdit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationInputs.Rule body,HttpServletRequest r){var actor=require(r,"notification-config:read","notification-config:write");return configurations.rule(actor,tenant(r),id,key,body,request(r));}
}
