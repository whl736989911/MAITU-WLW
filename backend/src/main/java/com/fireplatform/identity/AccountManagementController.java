package com.fireplatform.identity;

import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountManagementController {
    private final RequestSecurity security; private final IdentityQueries queries; private final AccountManagementService commands;
    public AccountManagementController(RequestSecurity security,IdentityQueries queries,AccountManagementService commands) { this.security=security; this.queries=queries; this.commands=commands; }
    @GetMapping("/api/{audience:management|customer}/users") public Page<IdentityQueries.ManagedUser> customers(@PathVariable String audience,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,HttpServletRequest request) {
        var actor=security.require(request,"users:read",audience);
        if("PLATFORM".equals(actor.user().userType())) return queries.authorizedCustomers(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size,keyword);
        return queries.users("CUSTOMER",security.tenant(request),page,size,keyword);
    }
    @PostMapping("/api/{audience:management|customer}/users") public IdentityQueries.ManagedUser createCustomer(@PathVariable String audience,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ManagementInputs.CreateCustomer body,HttpServletRequest request) {
        var actor=security.require(request,"users:write",audience); return commands.createCustomer(actor,security.tenant(request),key,body,RequestContext.requestId(request));
    }
    @PutMapping("/api/{audience:management|customer}/users/{id}") public IdentityQueries.ManagedUser updateCustomer(@PathVariable String audience,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ManagementInputs.UpdateCustomer body,HttpServletRequest request) {
        var actor=security.require(request,"users:write",audience); return commands.updateCustomer(actor,security.tenant(request),id,key,body,RequestContext.requestId(request));
    }
    @PostMapping("/api/{audience:management|customer}/users/{id}/reset-password") public IdentityQueries.ManagedUser resetCustomer(@PathVariable String audience,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ManagementInputs.ResetPassword body,HttpServletRequest request) {
        var actor=security.require(request,"users:reset-password",audience); return commands.reset(actor,"CUSTOMER",security.tenant(request),id,key,body,RequestContext.requestId(request));
    }
    @GetMapping("/api/management/platform-users") public Page<IdentityQueries.ManagedUser> platforms(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,HttpServletRequest request) {
        security.require(request,"platform-users:read","management"); return queries.users("PLATFORM",null,page,size,keyword);
    }
    @PostMapping("/api/management/platform-users") public IdentityQueries.ManagedUser createPlatform(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ManagementInputs.CreatePlatform body,HttpServletRequest request) {
        var actor=security.require(request,"platform-users:write","management"); return commands.createPlatform(actor,key,body,RequestContext.requestId(request));
    }
    @PostMapping("/api/management/client-platform-operator")
    public IdentityQueries.ManagedUser createClientPlatformOperator(
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody ManagementInputs.CreateClientPlatformOperator body,HttpServletRequest request) {
        var actor=security.require(request,"platform-users:write","management");
        return commands.createClientPlatformOperator(actor,key,body,RequestContext.requestId(request));
    }
    @PutMapping("/api/management/platform-users/{id}") public IdentityQueries.ManagedUser updatePlatform(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ManagementInputs.UpdatePlatform body,HttpServletRequest request) {
        var actor=security.require(request,"platform-users:write","management"); return commands.updatePlatform(actor,id,key,body,RequestContext.requestId(request));
    }
    @PostMapping("/api/management/platform-users/{id}/reset-password") public IdentityQueries.ManagedUser resetPlatform(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ManagementInputs.ResetPassword body,HttpServletRequest request) {
        var actor=security.require(request,"platform-users:reset-password","management"); return commands.reset(actor,"PLATFORM",null,id,key,body,RequestContext.requestId(request));
    }
    @DeleteMapping("/api/{audience:management|customer}/users/{id}") public AccountManagementService.DeletionResult deleteCustomer(@PathVariable String audience,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IdentityLifecycleInput body,HttpServletRequest request) {
        var actor=security.require(request,"users:write",audience); return commands.deleteIfNoHistory(actor,"CUSTOMER",security.tenant(request),id,key,body,RequestContext.requestId(request));
    }
    @DeleteMapping("/api/management/platform-users/{id}") public AccountManagementService.DeletionResult deletePlatform(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IdentityLifecycleInput body,HttpServletRequest request) {
        var actor=security.require(request,"platform-users:write","management"); return commands.deleteIfNoHistory(actor,"PLATFORM",null,id,key,body,RequestContext.requestId(request));
    }
}
