package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Limits;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/** Frozen v1 role-template, user-authorization and device-scope endpoints. */
@RestController
public class AccessController {
    private final RequestSecurity security;
    private final AccessQueries queries;
    private final RoleAdminService roles;
    private final UserAccessService access;
    private final DeviceScopes scopes;

    public AccessController(RequestSecurity security, AccessQueries queries, RoleAdminService roles, UserAccessService access, DeviceScopes scopes) {
        this.security = security; this.queries = queries; this.roles = roles; this.access = access; this.scopes = scopes;
    }

    @GetMapping("/api/management/roles")
    public Page<AccessModels.Role> managementRoles(HttpServletRequest request) {
        security.require(request, "roles:read", "management");
        query(request, Set.of("user_type", "page", "size","keyword"));
        String userType = request.getParameter("user_type");
        if (!"PLATFORM".equals(userType) && !"CUSTOMER".equals(userType)) throw ApiError.bad("user_type必须为PLATFORM或CUSTOMER");
        return queries.roles(userType, page(request), size(request),request.getParameter("keyword"));
    }

    @GetMapping("/api/customer/roles")
    public Page<AccessModels.Role> customerRoles(HttpServletRequest request) {
        security.require(request, "roles:read", "customer");
        query(request, Set.of("page", "size","keyword"));
        return queries.roles("CUSTOMER", page(request), size(request),request.getParameter("keyword"));
    }

    @PostMapping("/api/management/roles")
    public AccessModels.Role createRole(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @Valid @RequestBody AccessInputs.CreateRole body, HttpServletRequest request) {
        var actor = security.require(request, "roles:write", "management");
        params(request, Set.of());
        return roles.create(actor, key, body, RequestContext.requestId(request));
    }

    @PutMapping("/api/management/roles/{id}")
    public AccessModels.Role updateRole(@PathVariable String id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @Valid @RequestBody AccessInputs.UpdateRole body, HttpServletRequest request) {
        var actor = security.require(request, "roles:write", "management");
        params(request, Set.of());
        return roles.update(actor, id, key, body, RequestContext.requestId(request));
    }

    @DeleteMapping("/api/management/roles/{id}")
    public AccessModels.Role deleteRole(@PathVariable String id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @Valid @RequestBody AccessInputs.DeleteRole body, HttpServletRequest request) {
        var actor = security.require(request, "roles:write", "management");
        params(request, Set.of());
        return roles.delete(actor, id, key, body, RequestContext.requestId(request));
    }

    @GetMapping("/api/{audience:management|customer}/users/{id}/access")
    public AccessModels.UserAccess customerAccess(@PathVariable String audience, @PathVariable String id, HttpServletRequest request) {
        security.require(request, "users:read", audience);
        query(request, Set.of());
        return access.readAccess("CUSTOMER", security.tenant(request), id);
    }

    @GetMapping("/api/management/platform-users/{id}/access")
    public AccessModels.UserAccess platformAccess(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "platform-users:read", "management");
        query(request, Set.of());
        return access.readAccess("PLATFORM", null, id);
    }

    @PutMapping("/api/{audience:management|customer}/users/{id}/access/roles")
    public AccessModels.UserAccess assignCustomerRoles(@PathVariable String audience, @PathVariable String id,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       @Valid @RequestBody AccessInputs.AssignRoles body, HttpServletRequest request) {
        var actor = security.require(request, "users:assign-roles", audience);
        params(request, Set.of());
        return access.assignRoles(actor, "CUSTOMER", security.tenant(request), id, key, body, RequestContext.requestId(request));
    }

    @PutMapping("/api/management/platform-users/{id}/access/roles")
    public AccessModels.UserAccess assignPlatformRoles(@PathVariable String id,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       @Valid @RequestBody AccessInputs.AssignRoles body, HttpServletRequest request) {
        var actor = security.require(request, "platform-users:assign-roles", "management");
        params(request, Set.of());
        return access.assignRoles(actor, "PLATFORM", null, id, key, body, RequestContext.requestId(request));
    }

    @GetMapping("/api/{audience:management|customer}/users/{id}/scope")
    public AccessModels.DeviceScope customerScope(@PathVariable String audience, @PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "users:read", audience);
        query(request, Set.of());
        return access.readScope(actor, "CUSTOMER", security.tenant(request), id);
    }

    @GetMapping("/api/management/platform-users/{id}/scope")
    public AccessModels.DeviceScope platformScope(@PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "platform-users:read", "management");
        query(request, Set.of());
        return access.readScope(actor, "PLATFORM", security.tenant(request), id);
    }

    @PutMapping("/api/{audience:management|customer}/users/{id}/scope")
    public AccessModels.DeviceScope assignCustomerScope(@PathVariable String audience, @PathVariable String id,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                        @Valid @RequestBody AccessInputs.AssignScope body, HttpServletRequest request) {
        var actor = security.require(request, "users:assign-scope", audience);
        params(request, Set.of());
        return access.assignScope(actor, "CUSTOMER", security.tenant(request), id, key, body, RequestContext.requestId(request));
    }

    @PutMapping("/api/management/platform-users/{id}/scope")
    public AccessModels.DeviceScope assignPlatformScope(@PathVariable String id,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                        @Valid @RequestBody AccessInputs.AssignScope body, HttpServletRequest request) {
        var actor = security.require(request, "platform-users:assign-scope", "management");
        params(request, Set.of());
        return access.assignScope(actor, "PLATFORM", security.tenant(request), id, key, body, RequestContext.requestId(request));
    }

    /** Read-only self scope: exact current owner state, never an implicit grant. */
    @GetMapping("/api/{audience:management|customer}/device-scope")
    public AccessModels.DeviceScope deviceScope(@PathVariable String audience, HttpServletRequest request) {
        var identity = security.identity(request);
        if (!identity.audience().equals(audience)) throw ApiError.forbidden("登录入口与资源受众不匹配");
        query(request, Set.of());
        String tenant = "customer".equals(audience) ? identity.user().tenantId() : security.tenant(request);
        return scopes.scope(identity.user().id(), tenant);
    }

    private void query(HttpServletRequest request, Set<String> allowed) {
        if (request.getContentLengthLong() > 0) throw ApiError.bad("此查询不接受请求体");
        params(request, allowed);
    }

    private void params(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet())
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) throw ApiError.bad("未知或重复查询参数");
    }

    private int page(HttpServletRequest request) { return integer(request.getParameter("page"), 1, 1, Limits.MAX_PAGE); }

    private Integer size(HttpServletRequest request) {
        String raw = request.getParameter("size");
        return raw == null ? null : integer(raw, 0, 1, Limits.MAX_PAGE_SIZE);
    }

    private int integer(String raw, int fallback, int min, int max) {
        if (raw == null) return fallback;
        if (!raw.matches("[1-9][0-9]*")) throw ApiError.bad("分页参数无效");
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) throw ApiError.bad("分页参数超出范围");
            return value;
        } catch (NumberFormatException e) {
            throw ApiError.bad("分页参数超出范围");
        }
    }
}
