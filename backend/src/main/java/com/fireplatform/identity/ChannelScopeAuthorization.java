package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only current authority for an already trusted internal session reference. No locks or credential transport. */
@Service
public class ChannelScopeAuthorization {
    private final AuthService auth;private final JdbcTemplate jdbc;
    public ChannelScopeAuthorization(AuthService auth,JdbcTemplate jdbc){this.auth=auth;this.jdbc=jdbc;}
    public AuthService.Identity require(String tokenHash,String audience,String tenant){
        var identity=auth.refreshKnownIdentity(tokenHash);
        if(identity.user().mustChangePassword())throw new ApiError(403,"PASSWORD_CHANGE_REQUIRED","首次登录请先修改密码");
        if(!identity.audience().equals(audience)||!identity.permissions().contains("alarm:read"))throw ApiError.forbidden("当前授权不允许报警通道");
        if(identity.user().userType().equals("CUSTOMER")){if(!tenant.equals(identity.user().tenantId()))throw ApiError.forbidden("客户单位不匹配");}
        else if(jdbc.queryForObject("SELECT COUNT(*) FROM effective_user_tenant_grants WHERE user_id=? AND tenant_id=?",Long.class,identity.user().id(),tenant)!=1L)throw ApiError.forbidden("单位授权已撤销");
        var enabled=jdbc.queryForList("SELECT enabled FROM tenants WHERE id=?",Boolean.class,tenant);
        if(enabled.isEmpty()||!enabled.getFirst())throw new ApiError(403,"UNIT_DISABLED","单位已停用");
        return identity;
    }
}
