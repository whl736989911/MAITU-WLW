package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.Limits;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountManagementService {
    private final JdbcTemplate jdbc; private final ManagementGuard guard; private final SecretCommandStore commands; private final PermissionCatalog catalog; private final AuthService auth; private final IdentityQueries queries; private final PasswordEncoder encoder; private final AuditService audit; private final ManagementBounds bounds; private final IdentityReferenceGuard references;
    public AccountManagementService(JdbcTemplate jdbc,ManagementGuard guard,SecretCommandStore commands,PermissionCatalog catalog,AuthService auth,IdentityQueries queries,PasswordEncoder encoder,AuditService audit,ManagementBounds bounds,IdentityReferenceGuard references) { this.jdbc=jdbc; this.guard=guard; this.commands=commands; this.catalog=catalog; this.auth=auth; this.queries=queries; this.encoder=encoder; this.audit=audit; this.bounds=bounds; this.references=references; }
    @Transactional public IdentityQueries.ManagedUser createCustomer(AuthService.Identity actor,String tenant,String key,ManagementInputs.CreateCustomer in,String requestId) {
        return create(actor,"CUSTOMER",tenant,key,in.username(),in.name(),in.initialPassword(),in.permissions(),List.of(),requestId);
    }
    @Transactional public IdentityQueries.ManagedUser createPlatform(AuthService.Identity actor,String key,ManagementInputs.CreatePlatform in,String requestId) {
        return create(actor,"PLATFORM",null,key,in.username(),in.name(),in.initialPassword(),in.permissions(),in.tenantIds(),requestId);
    }

    @Transactional public IdentityQueries.ManagedUser createClientPlatformOperator(
            AuthService.Identity actor,String key,ManagementInputs.CreateClientPlatformOperator in,String requestId) {
        guard.begin(actor,"platform-users:write",null,"management");
        if(!"bootstrap-admin".equals(actor.user().id())) throw ApiError.forbidden("仅平台根账号可创建客户端平台管理员");
        if(!Limits.validPassword(in.initialPassword())) throw ApiError.bad("目标密码不符合策略");
        String target=AuthService.CLIENT_PLATFORM_OPERATOR_ID;
        String operation="client-platform-operator-create";
        var permissions=PermissionCatalog.CUSTOMER_KEYS.stream().sorted().toList();
        String hash=commands.hashBusiness(key,List.of(in.username(),in.name(),permissions));
        var replay=commands.replay(actor.user().id(),operation,key,hash,in.initialPassword(),IdentityQueries.ManagedUser.class);
        if(replay!=null) return replay;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=?",Long.class,target)!=0)
            throw ApiError.conflict("CLIENT_PLATFORM_OPERATOR_EXISTS","客户端平台管理员已存在");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?",Long.class,in.username())!=0)
            throw ApiError.conflict("USERNAME_CONFLICT","用户名已被使用");
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,revision,is_protected) VALUES (?,?,?,'PLATFORM',?,TRUE,TRUE,0,TRUE)",
                target,in.username(),in.name(),encoder.encode(in.initialPassword()));
        assign(target,permissions,List.of());
        var result=queries.target(target,"PLATFORM",null,false);
        audit.record(actor.user().id(),null,"CREATE_CLIENT_PLATFORM_OPERATOR",target,"SUCCESS",requestId);
        commands.save(actor.user().id(),operation,key,hash,in.initialPassword(),result);
        return result;
    }
    private IdentityQueries.ManagedUser create(AuthService.Identity actor,String type,String tenant,String key,String username,String name,String initial,List<String> permissions,List<String> grants,String requestId) {
        String operation=type.equals("PLATFORM") ? "platform-create" : "customer-create";
        guard.begin(actor,type.equals("PLATFORM") ? "platform-users:write" : "users:write",tenant,type.equals("PLATFORM") ? "management" : null);
        var allowed=catalog.validate(type,permissions,auth.permissions(actor.user().id())); var delegated=type.equals("PLATFORM") ? guard.grants(actor.user().id(),grants) : List.<String>of();
        String hash=commands.hashBusiness(key,List.of(type,tenant==null ? "" : tenant,username,name,allowed,delegated));
        var replay=commands.replay(actor.user().id(),operation,key,hash,initial,IdentityQueries.ManagedUser.class); if(replay!=null) return replay;
        if(!Limits.validPassword(initial)) throw ApiError.bad(Limits.PASSWORD_REQUIREMENTS);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?",Long.class,username)!=0) throw ApiError.conflict("USERNAME_CONFLICT","用户名已被使用");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,revision,is_protected) VALUES (?,?,?,?,?,?,TRUE,TRUE,0,FALSE)",id,username,name,type,tenant,encoder.encode(initial));
        assign(id,allowed,delegated); var result=queries.target(id,type,tenant,false);
        audit.record(actor.user().id(),tenant,operation.toUpperCase(java.util.Locale.ROOT),id,"SUCCESS",requestId); commands.save(actor.user().id(),operation,key,hash,initial,result); return result;
    }
    @Transactional public IdentityQueries.ManagedUser updateCustomer(AuthService.Identity actor,String tenant,String id,String key,ManagementInputs.UpdateCustomer in,String requestId) {
        return update(actor,"CUSTOMER",tenant,id,key,in.name(),in.enabled(),in.permissions(),List.of(),in.expectedRevision(),requestId);
    }
    @Transactional public IdentityQueries.ManagedUser updatePlatform(AuthService.Identity actor,String id,String key,ManagementInputs.UpdatePlatform in,String requestId) {
        return update(actor,"PLATFORM",null,id,key,in.name(),in.enabled(),in.permissions(),in.tenantIds(),in.expectedRevision(),requestId);
    }
    private IdentityQueries.ManagedUser update(AuthService.Identity actor,String type,String tenant,String id,String key,String name,boolean enabled,List<String> permissions,List<String> grants,long revision,String requestId) {
        String operation=type.equals("PLATFORM") ? "platform-update" : "customer-update";
        guard.begin(actor,type.equals("PLATFORM") ? "platform-users:write" : "users:write",tenant,type.equals("PLATFORM") ? "management" : null);
        var target=queries.target(id,type,tenant,true); bounds.protect(actor,target); bounds.check(actor,target);
        var allowed=catalog.validate(type,permissions,auth.permissions(actor.user().id())); var delegated=type.equals("PLATFORM") ? guard.grants(actor.user().id(),grants) : List.<String>of();
        String hash=commands.hashBusiness(key,List.of(type,tenant==null ? "" : tenant,id,name,enabled,allowed,delegated,revision));
        var replay=commands.replay(actor.user().id(),operation,key,hash,null,IdentityQueries.ManagedUser.class); if(replay!=null) return replay;
        if(target.revision()!=revision) throw ApiError.conflict("REVISION_CONFLICT","账号已更新，请刷新");
        jdbc.update("UPDATE app_users SET display_name=?,enabled=?,revision=revision+1 WHERE id=?",name,enabled,id);
        jdbc.update("DELETE FROM user_permissions WHERE user_id=?",id); jdbc.update("DELETE FROM user_tenant_grants WHERE user_id=?",id); assign(id,allowed,delegated);
        // Removing a platform grant also removes its live scope/member rows; re-adding a grant never restores an old scope.
        if(type.equals("PLATFORM")) {
            jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id=? AND tenant_id NOT IN (SELECT tenant_id FROM user_tenant_grants WHERE user_id=?)",id,id);
            jdbc.update("DELETE FROM user_device_scopes WHERE user_id=? AND tenant_id NOT IN (SELECT tenant_id FROM user_tenant_grants WHERE user_id=?)",id,id);
        }
        revoke(id);
        var result=queries.target(id,type,tenant,false); audit.record(actor.user().id(),tenant,operation.toUpperCase(java.util.Locale.ROOT),id,"SUCCESS",requestId); commands.save(actor.user().id(),operation,key,hash,null,result); return result;
    }
    @Transactional public IdentityQueries.ManagedUser reset(AuthService.Identity actor,String type,String tenant,String id,String key,ManagementInputs.ResetPassword in,String requestId) {
        if(!java.util.Set.of("PLATFORM","CUSTOMER").contains(type)) throw ApiError.bad("账号类型无效");
        String operation=type.equals("PLATFORM") ? "platform-reset" : "customer-reset";
        guard.begin(actor,type.equals("PLATFORM") ? "platform-users:reset-password" : "users:reset-password",tenant,type.equals("PLATFORM") ? "management" : null);
        var target=queries.target(id,type,tenant,true); bounds.protect(actor,target); bounds.check(actor,target);
        String hash=commands.hashBusiness(key,List.of(type,tenant==null ? "" : tenant,id,in.expectedRevision()));
        var replay=commands.replay(actor.user().id(),operation,key,hash,in.newPassword(),IdentityQueries.ManagedUser.class); if(replay!=null) return replay;
        if(!Limits.validPassword(in.newPassword())) throw ApiError.bad(Limits.PASSWORD_REQUIREMENTS);
        if(target.revision()!=in.expectedRevision()) throw ApiError.conflict("REVISION_CONFLICT","账号已更新，请刷新");
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=TRUE,revision=revision+1 WHERE id=?",encoder.encode(in.newPassword()),id); revoke(id);
        var result=queries.target(id,type,tenant,false); audit.record(actor.user().id(),tenant,operation.toUpperCase(java.util.Locale.ROOT),id,"SUCCESS",requestId); commands.save(actor.user().id(),operation,key,hash,in.newPassword(),result); return result;
    }
    private void assign(String id,List<String> permissions,List<String> grants) {
        for(String permission:permissions) jdbc.update("INSERT INTO user_permissions(user_id,permission) VALUES (?,?)",id,permission);
        for(String tenant:grants) jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES (?,?)",id,tenant);
    }
    private void revoke(String id) { jdbc.update("UPDATE sessions SET revoked=TRUE WHERE user_id=?",id); }
    public record DeletionResult(String id,String outcome,String reason) { }
    @Transactional public DeletionResult deleteIfNoHistory(AuthService.Identity actor,String type,String tenant,String id,String key,IdentityLifecycleInput input,String requestId) {
        if(!java.util.Set.of("CUSTOMER","PLATFORM").contains(type))throw ApiError.bad("账号类型无效");
        String operation=type.equals("PLATFORM")?"platform-user-delete":"customer-user-delete";
        guard.begin(actor,type.equals("PLATFORM")?"platform-users:write":"users:write",tenant,type.equals("PLATFORM")?"management":null);
        String reason=AccessInputs.reason(input.reason());
        String hash=commands.hashBusiness(key,List.of(type,tenant==null?"":tenant,id,input.expectedRevision(),reason));
        var replay=commands.replay(actor.user().id(),operation,key,hash,null,DeletionResult.class);if(replay!=null)return replay;
        var target=queries.target(id,type,tenant,true);bounds.protect(actor,target);bounds.check(actor,target);
        if(target.revision()!=input.expectedRevision())throw ApiError.conflict("REVISION_CONFLICT","账号已更新，请刷新");
        references.requireNoReferences("app_users",id,java.util.Set.of("sessions","user_permissions","user_tenant_grants","access_user_roles","user_device_scopes","user_device_scope_devices"),"账号存在审计或业务历史引用，不能删除；请保留并停用");
        jdbc.update("DELETE FROM user_device_scope_devices WHERE user_id=?",id);
        jdbc.update("DELETE FROM user_device_scopes WHERE user_id=?",id);
        jdbc.update("DELETE FROM access_user_roles WHERE user_id=?",id);
        jdbc.update("DELETE FROM user_tenant_grants WHERE user_id=?",id);
        jdbc.update("DELETE FROM user_permissions WHERE user_id=?",id);
        jdbc.update("DELETE FROM sessions WHERE user_id=?",id);
        if(jdbc.update("DELETE FROM app_users WHERE id=? AND revision=?",id,input.expectedRevision())!=1)throw ApiError.conflict("REVISION_CONFLICT","账号已更新，请刷新");
        var result=new DeletionResult(id,"DELETED",reason);
        audit.record(actor.user().id(),tenant,"DELETE_USER_NO_HISTORY",id,"SUCCESS",requestId);
        commands.save(actor.user().id(),operation,key,hash,null,result);
        return result;
    }
}
