package com.fireplatform.notification;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/notification-settings")
public class CustomerNotificationSettingsController {
    private final RequestSecurity security; private final CustomerNotificationSettingsService service;
    public CustomerNotificationSettingsController(RequestSecurity security,CustomerNotificationSettingsService service){this.security=security;this.service=service;}
    private AuthService.Identity read(HttpServletRequest request){return security.require(request,"unit-notifications:read","customer");}
    private AuthService.Identity write(HttpServletRequest request){read(request);return security.require(request,"unit-notifications:write","customer");}
    @GetMapping public Map<String,Object> settings(HttpServletRequest request){return service.settings(read(request));}
    @GetMapping("/contacts") public Page<Map<String,Object>> contacts(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,@RequestParam(required=false)String keyword,HttpServletRequest request){return service.contacts(read(request),page,size,keyword);}
    @GetMapping("/rules/{id}") public Map<String,Object> rule(@PathVariable String id,HttpServletRequest request){return service.rule(read(request),id);}
    @GetMapping("/rules/effective") public Map<String,Object> effectiveRule(@RequestParam String deviceId,@RequestParam String eventScope,HttpServletRequest request){return service.effectiveRule(read(request),deviceId,eventScope);}
    @GetMapping("/wechat-recipients") public java.util.List<com.fireplatform.notification.officialaccount.WechatOfficialAccountBindingService.RecipientView> wechatRecipients(HttpServletRequest request){return service.wechatRecipients(read(request));}
    @GetMapping("/wechat-channel") public Map<String,Object> wechatChannel(HttpServletRequest request){return service.wechatChannel(read(request));}
    @PostMapping("/contacts") public ResponseEntity<Map<String,Object>> createContact(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationInputs.Contact body,HttpServletRequest request){return ResponseEntity.status(201).body(service.contact(write(request),null,key,body,RequestContext.requestId(request)));}
    @PutMapping("/contacts/{id}") public Map<String,Object> updateContact(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationInputs.Contact body,HttpServletRequest request){return service.contact(write(request),id,key,body,RequestContext.requestId(request));}
    @GetMapping("/rules") public Page<Map<String,Object>> rules(@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size,@RequestParam(required=false)String keyword,@RequestParam(required=false)String scopeType,@RequestParam(required=false)String scopeId,HttpServletRequest request){return service.rules(read(request),page,size,keyword,scopeType,scopeId);}
    @PostMapping("/rules") public ResponseEntity<Map<String,Object>> createRule(@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationInputs.Rule body,HttpServletRequest request){return ResponseEntity.status(201).body(service.rule(write(request),null,key,body,RequestContext.requestId(request)));}
    @PutMapping("/rules/{id}") public Map<String,Object> updateRule(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody NotificationInputs.Rule body,HttpServletRequest request){return service.rule(write(request),id,key,body,RequestContext.requestId(request));}
}
