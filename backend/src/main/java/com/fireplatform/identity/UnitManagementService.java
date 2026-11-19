package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnitManagementService {
    private final JdbcTemplate jdbc; private final ManagementGuard guard; private final SecretCommandStore commands; private final IdentityQueries queries; private final UnitDirectoryQueries directory; private final AuditService audit; private final IdentityReferenceGuard references;
    public UnitManagementService(JdbcTemplate jdbc,ManagementGuard guard,SecretCommandStore commands,IdentityQueries queries,UnitDirectoryQueries directory,AuditService audit,IdentityReferenceGuard references) { this.jdbc=jdbc; this.guard=guard; this.commands=commands; this.queries=queries; this.directory=directory; this.audit=audit; this.references=references; }
    @Transactional public IdentityQueries.Unit create(AuthService.Identity actor,String key,ManagementInputs.CreateUnit input,String requestId) {
        guard.begin(actor,"units:write",null,"management");
        String hash=commands.hashBusiness(key,List.of(input.name()));
        var replay=commands.replay(actor.user().id(),"unit-create",key,hash,null,IdentityQueries.Unit.class); if(replay!=null) return replay;
        String id=UUID.randomUUID().toString(); jdbc.update("INSERT INTO tenants(id,name,enabled,revision) VALUES (?,?,TRUE,0)",id,input.name());
        // Explicit contract exception: creation's new-unit grant does not revoke the creator or change its revision.
        jdbc.update("INSERT INTO user_tenant_grants(user_id,tenant_id) VALUES (?,?)",actor.user().id(),id);
        // The automatic creator grant carries an explicit ALL_TENANT scope (including future devices) in the same transaction.
        jdbc.update("INSERT INTO user_device_scopes(user_id,tenant_id,mode,revision) VALUES (?,?,'ALL_TENANT',1)",actor.user().id(),id);
        var result=queries.unit(id); audit.record(actor.user().id(),id,"CREATE_UNIT",id,"SUCCESS",requestId); commands.save(actor.user().id(),"unit-create",key,hash,null,result); return result;
    }
    @Transactional public IdentityQueries.Unit update(AuthService.Identity actor,String id,String key,ManagementInputs.UpdateUnit input,String requestId) {
        guard.begin(actor,"units:write",null,"management"); guard.grantRequired(actor.user().id(),id);
        String hash=commands.hashBusiness(key,List.of(id,input.name(),input.enabled(),input.expectedRevision()));
        var replay=commands.replay(actor.user().id(),"unit-update",key,hash,null,IdentityQueries.Unit.class); if(replay!=null) return replay;
        // Lock customer users BEFORE the tenant row, matching ordinary user -> tenant write order.
        // This also serializes customer login/password transactions before bulk session revocation.
        if(!input.enabled()) jdbc.queryForList("SELECT id FROM app_users WHERE tenant_id=? AND user_type='CUSTOMER' ORDER BY id FOR UPDATE",String.class,id);
        var rows=jdbc.queryForList("SELECT revision FROM tenants WHERE id=? FOR UPDATE",Long.class,id);
        if(rows.isEmpty()) throw ApiError.missing(); if(!rows.getFirst().equals(input.expectedRevision())) throw ApiError.conflict("REVISION_CONFLICT","单位已更新，请刷新");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM tenants WHERE id=?",Boolean.class,id))) throw ApiError.conflict("UNIT_ARCHIVED","已归档单位不可重新启用或修改");
        jdbc.update("UPDATE tenants SET name=?,enabled=?,revision=revision+1 WHERE id=?",input.name(),input.enabled(),id);
        if(!input.enabled()) jdbc.update("UPDATE sessions SET revoked=TRUE WHERE user_id IN (SELECT id FROM app_users WHERE tenant_id=? AND user_type='CUSTOMER')",id);
        var result=queries.unit(id); audit.record(actor.user().id(),id,"UPDATE_UNIT",id,"SUCCESS",requestId); commands.save(actor.user().id(),"unit-update",key,hash,null,result); return result;
    }
    public record LifecycleResult(String id,String outcome,String reason) { }
    @Transactional public UnitDirectoryModels.UnitDetails archive(AuthService.Identity actor,String id,String key,IdentityLifecycleInput input,String requestId) {
        guard.begin(actor,"units:write",null,"management"); guard.grantRequired(actor.user().id(),id);
        String reason=AccessInputs.reason(input.reason());
        String hash=commands.hashBusiness(key,List.of(id,input.expectedRevision(),reason));
        var replay=commands.replay(actor.user().id(),"unit-archive",key,hash,null,UnitDirectoryModels.UnitDetails.class); if(replay!=null)return replay;
        jdbc.queryForList("SELECT id FROM app_users WHERE tenant_id=? AND user_type='CUSTOMER' ORDER BY id FOR UPDATE",String.class,id);
        var rows=jdbc.queryForList("SELECT revision FROM tenants WHERE id=? FOR UPDATE",Long.class,id);
        if(rows.isEmpty())throw ApiError.missing();
        if(!rows.getFirst().equals(input.expectedRevision()))throw ApiError.conflict("REVISION_CONFLICT","单位已更新，请刷新");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND archived_at IS NULL",Long.class,id)>0)
            throw ApiError.conflict("UNIT_HAS_ACTIVE_DEVICES","单位仍有活动设备。请先逐一移交或明确归档设备；不得自动改变设备或来源归属");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM active_source_bindings b JOIN devices d ON d.id=b.device_id WHERE d.tenant_id=?",Long.class,id)>0
                || jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices h JOIN devices d ON d.id=h.local_device_id WHERE d.tenant_id=?",Long.class,id)>0)
            throw ApiError.conflict("UNIT_HAS_BOUND_SOURCES","单位仍有活动设备来源绑定。请先在对应来源边界明确解绑；历史引用可保留");
        int changed=jdbc.update("UPDATE tenants SET enabled=FALSE,archived_at=CURRENT_TIMESTAMP,archived_reason=?,archived_by=?,revision=revision+1 WHERE id=? AND revision=? AND archived_at IS NULL",reason,actor.user().id(),id,input.expectedRevision());
        if(changed!=1)throw ApiError.conflict("REVISION_CONFLICT","单位已更新或已归档，请刷新");
        jdbc.update("UPDATE sessions SET revoked=TRUE WHERE user_id IN (SELECT id FROM app_users WHERE tenant_id=? AND user_type='CUSTOMER')",id);
        var result=directory.detail(actor.user().id(),id); audit.record(actor.user().id(),id,"ARCHIVE_UNIT",id,"SUCCESS",requestId); commands.save(actor.user().id(),"unit-archive",key,hash,null,result); return result;
    }
    @Transactional public LifecycleResult deleteIfEmpty(AuthService.Identity actor,String id,String key,IdentityLifecycleInput input,String requestId) {
        guard.begin(actor,"units:write",null,"management"); guard.grantRequired(actor.user().id(),id);
        String reason=AccessInputs.reason(input.reason());
        String hash=commands.hashBusiness(key,List.of(id,input.expectedRevision(),reason));
        var replay=commands.replay(actor.user().id(),"unit-delete",key,hash,null,LifecycleResult.class); if(replay!=null)return replay;
        jdbc.queryForList("SELECT id FROM app_users WHERE tenant_id=? ORDER BY id FOR UPDATE",String.class,id);
        var tenant=jdbc.queryForList("SELECT revision FROM tenants WHERE id=? FOR UPDATE",Long.class,id);
        if(tenant.isEmpty())throw ApiError.missing();
        if(!tenant.getFirst().equals(input.expectedRevision()))throw ApiError.conflict("REVISION_CONFLICT","单位已更新，请刷新");
        references.requireNoReferences("tenants",id,java.util.Set.of("user_tenant_grants","user_device_scopes","user_device_scope_devices"),"单位存在业务或历史引用，不能删除；请明确归档/停用");
        var grants=jdbc.queryForList("SELECT user_id FROM user_tenant_grants WHERE tenant_id=? ORDER BY user_id FOR UPDATE",String.class,id);
        for(String user:grants) {
            RoleAdminService.requireNextRevision(jdbc.queryForObject("SELECT revision FROM app_users WHERE id=?",Long.class,user));
            jdbc.update("UPDATE app_users SET revision=revision+1 WHERE id=?",user);
            jdbc.update("UPDATE sessions SET revoked=TRUE WHERE user_id=?",user);
        }
        jdbc.update("DELETE FROM user_device_scope_devices WHERE tenant_id=?",id);
        jdbc.update("DELETE FROM user_device_scopes WHERE tenant_id=?",id);
        jdbc.update("DELETE FROM user_tenant_grants WHERE tenant_id=?",id);
        if(jdbc.update("DELETE FROM tenants WHERE id=? AND revision=?",id,input.expectedRevision())!=1)throw ApiError.conflict("REVISION_CONFLICT","单位已更新，请刷新");
        var result=new LifecycleResult(id,"DELETED",reason);
        audit.record(actor.user().id(),null,"DELETE_EMPTY_UNIT",id,"SUCCESS",requestId);
        commands.save(actor.user().id(),"unit-delete",key,hash,null,result);
        return result;
    }
}
