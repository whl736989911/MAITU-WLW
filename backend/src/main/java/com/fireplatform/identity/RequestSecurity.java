package com.fireplatform.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RequestSecurity extends OncePerRequestFilter {
    private static final String IDENTITY=RequestSecurity.class.getName()+".identity";
    private final AuthService auth;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public RequestSecurity(AuthService auth,ObjectMapper mapper,JdbcTemplate jdbc,AuditService audit) { this.auth=auth; this.mapper=mapper; this.jdbc=jdbc; this.audit=audit; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        try {
            if (!path.startsWith("/api/") || (path.equals("/api/health") && request.getMethod().equals("GET")) || (path.equals("/api/auth/login") && request.getMethod().equals("POST")) || (request.getMethod().equals("POST") && path.matches("/api/hooks/hikvision/[0-9a-f]{64}")) || (request.getMethod().equals("POST") && path.matches("/api/ingest/[A-Za-z0-9_-]{1,64}/messages")) || (request.getMethod().equals("POST") && path.matches("/api/notify/v1/[A-Za-z0-9_-]{1,64}/[A-Za-z0-9_]{1,32}/telemetry")) || (request.getMethod().equals("POST") && path.equals("/api/payments/wechat/native/notify")) || (request.getMethod().equals("POST") && path.matches("/api/ingest/(ttl|cat1)(/.*)?")) || (request.getMethod().equals("GET") && path.equals("/api/wechat/oauth/callback")) || ((request.getMethod().equals("GET") || request.getMethod().equals("POST")) && path.equals("/api/wechat/callback"))) { chain.doFilter(request,response); return; }
            // Video media GETs carry an HttpOnly SameSite cookie, never a bearer/query token: the media
            // controller re-validates the session's current user, permission, device scope and expiry.
            if(videoMedia(request,path)) { chain.doFilter(request,response); return; }
            // Only these exact raw Upgrade routes delegate pre-101 identity/scope checks to the ticket gate.
            if(channelUpgrade(request)) { chain.doFilter(request,response); return; }
            String header=request.getHeader("Authorization");
            if(header==null || !header.startsWith("Bearer ")) throw AuthService.unauthorized();
            var identity=auth.authenticate(header.substring(7)); request.setAttribute(IDENTITY,identity);
            RequestContext.actor(request,identity.user().id(),identity.user().tenantId());
            if(identity.user().userType().equals("CUSTOMER") && request.getHeader("X-Tenant-Id")!=null) throw ApiError.forbidden("客户租户由服务端会话确定，禁止X-Tenant-Id");
            if((path.startsWith("/api/management/") && !identity.audience().equals("management")) || (path.startsWith("/api/customer/") && !identity.audience().equals("customer"))) throw ApiError.forbidden("登录入口与资源受众不匹配");
            if(identity.user().mustChangePassword() && !path.equals("/api/auth/me") && !path.equals("/api/auth/change-password") && !path.equals("/api/auth/logout")) throw new ApiError(403,"PASSWORD_CHANGE_REQUIRED","首次登录请先修改密码");
            chain.doFilter(request,response);
        } catch(ApiError e) {
            try { audit.record(RequestContext.actorId(request),RequestContext.tenantId(request),"REQUEST",null,"DENIED",RequestContext.requestId(request)); }
            catch(DataAccessException failure) { error(response,request,503,"STORAGE_UNAVAILABLE","存储操作失败，请稍后重试"); return; }
            error(response,request,e.status,e.code,e.getMessage());
        } catch(DataAccessException e) { error(response,request,503,"STORAGE_UNAVAILABLE","存储操作失败，请稍后重试"); }
    }
    private static boolean videoMedia(HttpServletRequest request,String path) {
        return request.getMethod().equals("GET") && path.matches(
            "/api/(management|customer)/video-resources/[A-Za-z0-9-]{1,64}/media/(play|snapshot|hls/[0-9]{1,4})");
    }
    private static boolean channelUpgrade(HttpServletRequest request) {
        String path=request.getRequestURI();
        return request.getMethod().equals("GET") && java.util.Set.of("/api/management/alerts","/api/customer/alerts").contains(path)
            && "websocket".equalsIgnoreCase(request.getHeader("Upgrade")) && request.getHeader("Connection")!=null
            && java.util.Arrays.stream(request.getHeader("Connection").split(",")).anyMatch(v->v.trim().equalsIgnoreCase("upgrade"));
    }
    private void error(HttpServletResponse response,HttpServletRequest request,int status,String code,String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); mapper.writeValue(response.getOutputStream(),new ApiError.Body(code,message,RequestContext.requestId(request)));
    }
    public AuthService.Identity identity(HttpServletRequest request) {
        var identity=(AuthService.Identity) request.getAttribute(IDENTITY); if(identity==null) throw AuthService.unauthorized(); return identity;
    }
    public AuthService.Identity require(HttpServletRequest request,String permission,String audience) {
        var identity=identity(request);
        if(!identity.audience().equals(audience)) throw ApiError.forbidden("登录入口与资源受众不匹配");
        if(!identity.permissions().contains(permission)) throw ApiError.forbidden("没有此操作权限"); return identity;
    }
    public String tenant(HttpServletRequest request) {
        var identity=identity(request); if(identity.user().userType().equals("CUSTOMER")) return identity.user().tenantId();
        String tenant=request.getHeader("X-Tenant-Id"); if(tenant==null || tenant.isBlank() || tenant.length()>64) throw ApiError.bad("管理租户业务必须选择已授权的X-Tenant-Id");
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?",Long.class,identity.user().id(),tenant);
        if(count==null || count==0) throw ApiError.forbidden("未授权访问此租户");
        if(!auth.tenantEnabled(tenant)) throw new ApiError(403,"UNIT_DISABLED","单位已停用"); return tenant;
    }
    /** Validates an optional per-page tenant filter without requiring the global tenant header. */
    public String tenantFilter(HttpServletRequest request,String tenantId) {
        if(tenantId==null || tenantId.isBlank()) return null;
        var identity=identity(request); if(identity.user().userType().equals("CUSTOMER")) return identity.user().tenantId();
        if(tenantId.length()>64) throw ApiError.bad("租户ID无效");
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?",Long.class,identity.user().id(),tenantId);
        if(count==null || count==0) throw ApiError.forbidden("未授权访问此租户");
        if(!auth.tenantEnabled(tenantId)) throw new ApiError(403,"UNIT_DISABLED","单位已停用"); return tenantId;
    }
    /** Validates a multi-unit page scope. Empty input means the caller did not request a narrower scope. */
    public java.util.List<String> tenantFilters(HttpServletRequest request,java.util.List<String> tenantIds) {
        if(tenantIds==null || tenantIds.isEmpty()) return java.util.List.of();
        var identity=identity(request);
        if(identity.user().userType().equals("CUSTOMER")) {
            if(tenantIds.stream().anyMatch(id -> !id.equals(identity.user().tenantId()))) throw ApiError.forbidden("客户范围由服务端会话确定");
            return java.util.List.of(identity.user().tenantId());
        }
        if(tenantIds.size()>100) throw ApiError.bad("单位范围超过上限");
        var result=new java.util.LinkedHashSet<String>();
        for(String tenantId:tenantIds) {
            if(tenantId==null || tenantId.isBlank() || tenantId.length()>64) throw ApiError.bad("单位ID无效");
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?",Long.class,identity.user().id(),tenantId);
            if(count==null || count==0) throw ApiError.forbidden("未授权访问此单位");
            if(!auth.tenantEnabled(tenantId)) throw new ApiError(403,"UNIT_DISABLED","单位已停用");
            result.add(tenantId);
        }
        return java.util.List.copyOf(result);
    }

    /** Platform reads may omit X-Tenant-Id to aggregate all authorized tenants; customer always stays tenant-bound. */
    public String optionalTenant(HttpServletRequest request) {
        var identity=identity(request); if(identity.user().userType().equals("CUSTOMER")) return identity.user().tenantId();
        String tenant=request.getHeader("X-Tenant-Id"); if(tenant==null || tenant.isBlank()) return null;
        if(tenant.length()>64) throw ApiError.bad("租户ID无效");
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM user_tenant_grants WHERE user_id=? AND tenant_id=?",Long.class,identity.user().id(),tenant);
        if(count==null || count==0) throw ApiError.forbidden("未授权访问此租户");
        if(!auth.tenantEnabled(tenant)) throw new ApiError(403,"UNIT_DISABLED","单位已停用"); return tenant;
    }

    /** Explicit empty selection is distinct from an omitted, unrestricted read filter. */
    public com.fireplatform.shared.ScopeFilter.Selection readSelection(HttpServletRequest request) {
        var tenants = tenantFilters(request, scopeIds(request, "scope_tenant_ids", 100));
        var places = scopeIds(request, "scope_place_ids", 200);
        var actor = identity(request);
        for (String place : places) {
            Long count = actor.user().userType().equals("CUSTOMER")
                ? jdbc.queryForObject("SELECT COUNT(*) FROM places p JOIN tenants t ON t.id=p.tenant_id WHERE p.id=? AND p.tenant_id=? AND t.enabled=TRUE", Long.class, place, actor.user().tenantId())
                : jdbc.queryForObject("SELECT COUNT(*) FROM places p JOIN tenants t ON t.id=p.tenant_id JOIN user_tenant_grants g ON g.tenant_id=t.id WHERE p.id=? AND g.user_id=? AND t.enabled=TRUE", Long.class, place, actor.user().id());
            if (count == null || count == 0) throw ApiError.forbidden("未授权访问此位置范围");
        }
        String selected = request.getParameter("scope_selected");
        if (selected != null && !selected.equals("true") && !selected.equals("false")) throw ApiError.bad("范围选择标记无效");
        boolean explicit = "true".equals(selected) || request.getParameterMap().containsKey("scope_tenant_ids") && request.getParameterMap().containsKey("scope_place_ids");
        return new com.fireplatform.shared.ScopeFilter.Selection(tenants, places, explicit && tenants.isEmpty() && places.isEmpty());
    }

    private static java.util.List<String> scopeIds(HttpServletRequest request, String name, int maximum) {
        String[] values = request.getParameterValues(name);
        if (values == null) return java.util.List.of();
        if (values.length != 1) throw ApiError.bad("范围参数不能重复");
        String value = values[0];
        if (value == null || value.isEmpty()) return java.util.List.of();
        String[] parts = value.split(",", -1);
        if (parts.length > maximum) throw ApiError.bad("范围筛选超过上限");
        var ids = new java.util.LinkedHashSet<String>();
        for (String part : parts) {
            if (part.isBlank() || part.length() > 64 || !part.equals(part.trim())) throw ApiError.bad("范围ID无效");
            ids.add(part);
        }
        return java.util.List.copyOf(ids);
    }
}
