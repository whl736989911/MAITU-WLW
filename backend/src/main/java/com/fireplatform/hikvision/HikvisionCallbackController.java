package com.fireplatform.hikvision;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class HikvisionCallbackController {
    private final RequestSecurity security; private final HikvisionCallbackService callbacks;
    public HikvisionCallbackController(RequestSecurity security,HikvisionCallbackService callbacks){this.security=security;this.callbacks=callbacks;}
    @GetMapping("/api/management/hikvision/callback")
    public HikvisionCallbackService.View view(HttpServletRequest request){security.require(request,"integrations:read","management");return callbacks.get();}
    @PutMapping("/api/management/hikvision/callback")
    public HikvisionCallbackService.View update(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody HikvisionCallbackService.Write body,HttpServletRequest request){var actor=security.require(request,"integrations:write","management");return callbacks.update(actor,key,body,RequestContext.requestId(request));}
    @GetMapping("/api/management/hikvision/callback/receipts")
    public Page<HikvisionCallbackService.Receipt> receipts(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request){security.require(request,"integrations:read","management");return callbacks.receipts(page,size);}
    @PostMapping("/api/management/hikvision/callback/receipts/{id}/reparse")
    public HikvisionCallbackService.Receipt reparse(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,
            @RequestBody HikvisionCallbackService.Reparse body,HttpServletRequest request){
        var actor=security.require(request,"integrations:write","management");
        return callbacks.reparse(actor,id,key,body,RequestContext.requestId(request));
    }
    @PostMapping("/api/management/hikvision/callback/subscriptions")
    public HikvisionCallbackService.SubscriptionResult subscriptions(HttpServletRequest request){var actor=security.require(request,"integrations:write","management");return callbacks.subscriptions(actor,RequestContext.requestId(request));}
    @PostMapping(value="/api/hooks/hikvision/{credential}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public org.springframework.http.ResponseEntity<Void> receive(@PathVariable String credential,HttpServletRequest request){
        try{byte[] raw=request.getInputStream().readNBytes(com.fireplatform.shared.Limits.MAX_JSON_BODY_BYTES+1);if(raw.length>com.fireplatform.shared.Limits.MAX_JSON_BODY_BYTES)return org.springframework.http.ResponseEntity.status(413).build();callbacks.receive(credential,raw);return org.springframework.http.ResponseEntity.ok().build();}
        catch(com.fireplatform.shared.ApiError error){return org.springframework.http.ResponseEntity.status(error.status).build();}
        catch(IOException e){return org.springframework.http.ResponseEntity.badRequest().build();}
    }
}
