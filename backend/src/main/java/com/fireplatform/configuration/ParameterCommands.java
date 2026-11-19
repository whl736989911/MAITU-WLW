package com.fireplatform.configuration;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParameterCommands {
    private record State(String id,Long value,long revision) { }
    private final JdbcTemplate jdbc; private final ExecutionAuthorization authorization; private final TenantFence tenants; private final ParameterQueries queries; private final IdempotencyStore idem; private final AuditService audit; private final DatabaseTime time; private final ManagementGuard management; private final DeviceAccessScope scopes;
    public ParameterCommands(JdbcTemplate jdbc,ExecutionAuthorization authorization,TenantFence tenants,ParameterQueries queries,IdempotencyStore idem,AuditService audit,DatabaseTime time,ManagementGuard management,DeviceAccessScope scopes) { this.jdbc=jdbc; this.authorization=authorization; this.tenants=tenants; this.queries=queries; this.idem=idem; this.audit=audit; this.time=time; this.management=management; this.scopes=scopes; }
    @Transactional public ParameterModels.Mutation set(AuthService.Identity actor,String tenant,String scope,String id,String key,String idemKey,ParameterInputs.SetValue in,String request) { return mutate(actor,tenant,scope,id,key,idemKey,"SET",in.value(),in.expectedRevision(),in.reason(),request,false); }
    @Transactional public ParameterModels.Mutation clear(AuthService.Identity actor,String tenant,String scope,String id,String key,String idemKey,ParameterInputs.Clear in,String request) { return mutate(actor,tenant,scope,id,key,idemKey,"CLEAR",null,in.expectedRevision(),in.reason(),request,false); }
    @Transactional public ParameterModels.Mutation setForCustomer(AuthService.Identity actor,String tenant,String key,String idemKey,ParameterInputs.SetValue in,String request) { return mutate(actor,tenant,"TENANT",tenant,key,idemKey,"SET",in.value(),in.expectedRevision(),in.reason(),request,true); }
    @Transactional public ParameterModels.Mutation clearForCustomer(AuthService.Identity actor,String tenant,String key,String idemKey,ParameterInputs.Clear in,String request) { return mutate(actor,tenant,"TENANT",tenant,key,idemKey,"CLEAR",null,in.expectedRevision(),in.reason(),request,true); }
    private ParameterModels.Mutation mutate(AuthService.Identity actor,String tenant,String scope,String id,String key,String idemKey,String action,Long value,long expected,String reason,String request,boolean customer) {
        if(customer) {
            management.begin(actor,"unit-parameters:write",tenant,"customer");
            management.requireCurrent(actor,"unit-parameters:read",tenant,"customer");
            scopes.requireAll(actor.user().id(),tenant);
            if(!OfflinePolicySql.KEY.equals(key) || !"TENANT".equals(scope) || !tenant.equals(id)) throw ApiError.forbidden("仅允许维护本单位已开放的参数");
        } else authorization.lockRequest(actor,tenant,List.of("parameters:write","parameters:read"));
        tenants.lockEnabled(tenant); queries.validate(actor.user().id(),tenant,scope,id,key,true); idem.validateKey(idemKey); text(reason);
        var def=Limits.SETTINGS.get(key); if(value!=null && (value<def.min() || value>def.max())) throw ApiError.bad("参数值超出允许范围");
        String hash=idem.fingerprint(Arrays.asList(tenant,scope,id,key,action,value,expected,reason)); String operation="parameter-"+action.toLowerCase(Locale.ROOT);
        var replay=idem.replay(actor.user().id(),operation,idemKey,hash,ParameterModels.ChangeReference.class); if(replay!=null) return new ParameterModels.Mutation(queries.view(actor.user().id(),tenant,scope,id,key),queries.change(tenant,scope,id,key,replay.changeId()));
        if(customer && value!=null) {
            Long ceiling=jdbc.queryForObject("SELECT setting_value FROM numeric_settings WHERE setting_key=? FOR UPDATE",Long.class,key);
            if(ceiling==null || value>Math.min(def.max(),ceiling)) throw ApiError.bad("单位自助值不得超过平台当前全局阈值；更大范围需平台明确调整");
        }
        var rows=jdbc.query("SELECT id,override_value,revision FROM parameter_overrides WHERE tenant_id=? AND scope=? AND scope_id=? AND parameter_key=? FOR UPDATE",(rs,n)->new State(rs.getString(1),rs.getObject(2,Long.class),rs.getLong(3)),tenant,scope,id,key); var old=rows.isEmpty()?new State(UUID.randomUUID().toString(),null,0):rows.getFirst();
        if(old.revision()!=expected) throw ApiError.conflict("REVISION_CONFLICT","覆盖已更新，请刷新后重开操作");
        if(action.equals("CLEAR") && old.value()==null) throw ApiError.conflict("NO_OVERRIDE","当前没有可清除的覆盖");
        if(old.revision()==0) jdbc.update("INSERT INTO parameter_overrides(id,tenant_id,scope,scope_id,device_id,parameter_key,override_value,place_id,revision) VALUES (?,?,?,?,?,?,?,?,1)",old.id(),tenant,scope,id,scope.equals("DEVICE")?id:null,key,value,scope.equals("PLACE")?id:null);
        else { int changed=jdbc.update("UPDATE parameter_overrides SET override_value=?,revision=revision+1 WHERE id=? AND revision=?",value,old.id(),expected); if(changed!=1) throw ApiError.conflict("REVISION_CONFLICT","覆盖已更新，请刷新后重开操作"); }
        String change=UUID.randomUUID().toString(); jdbc.update("INSERT INTO parameter_changes(id,override_id,tenant_id,scope,scope_id,parameter_key,action,previous_value,new_value,revision_before,revision_after,actor_id,reason,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",change,old.id(),tenant,scope,id,key,action,old.value(),value,old.revision(),old.revision()+1,actor.user().id(),reason,time.now());
        idem.save(actor.user().id(),operation,idemKey,hash,new ParameterModels.ChangeReference(change)); audit.record(actor.user().id(),tenant,"PARAMETER_"+action,change,"SUCCESS",request);
        return new ParameterModels.Mutation(queries.view(actor.user().id(),tenant,scope,id,key),queries.change(tenant,scope,id,key,change));
    }
    private void text(String value) {
        if(value==null || value.isBlank() || value.length()>Limits.MAX_CHANGE_REASON_LENGTH || value.indexOf(0)>=0) throw ApiError.bad("变更理由无效");
        try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)); } catch(CharacterCodingException e) { throw ApiError.bad("文本编码无效"); }
    }
}
