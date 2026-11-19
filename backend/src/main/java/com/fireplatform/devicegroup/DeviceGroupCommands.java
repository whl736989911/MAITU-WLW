package com.fireplatform.devicegroup;

import com.fireplatform.identity.*;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.fireplatform.devicegroup.DeviceGroupModels.*;

@Service
public class DeviceGroupCommands {
    private final JdbcTemplate jdbc;private final ManagementGuard guard;private final IdempotencyStore idem;private final AuditService audit;private final TransactionRequirements transactions;private final DeviceAccessScope scope;private final DeviceGroupQueries queries;
    public DeviceGroupCommands(JdbcTemplate jdbc,ManagementGuard guard,IdempotencyStore idem,AuditService audit,TransactionRequirements transactions,DeviceAccessScope scope,DeviceGroupQueries queries){this.jdbc=jdbc;this.guard=guard;this.idem=idem;this.audit=audit;this.transactions=transactions;this.scope=scope;this.queries=queries;}
    private void begin(AuthService.Identity actor,String tenant,String key){transactions.readCommitted();guard.begin(actor,"device:write",tenant,"management");idem.validateKey(key);}
    private Group replay(AuthService.Identity actor,String tenant,String target,String operation,String key,String hash){
        try{Group value=idem.replay(actor.user().id(),operation,key,scopedHash(actor,tenant,hash),Group.class);if(value==null)return null;DeviceGroupProjection.valid(value,tenant);if(target!=null&&!target.equals(value.id()))throw DeviceGroupProjection.unconfirmed();return value;}
        catch(IllegalStateException e){if("Stored response is invalid".equals(e.getMessage()))throw DeviceGroupProjection.unconfirmed();throw e;}
    }
    private String scopedHash(AuthService.Identity actor,String tenant,String hash){return scope.all(actor.user().id(),tenant)?hash:idem.fingerprint(List.of(hash,scope.fingerprint(actor.user().id(),tenant)));}
    private Group saved(AuthService.Identity actor,String tenant,String id,String operation,String key,String hash,String request){var value=queries.visibleGroup(actor.user().id(),tenant,id);audit.record(actor.user().id(),tenant,operation,id,"SUCCESS",request);idem.save(actor.user().id(),operation,key,scopedHash(actor,tenant,hash),value);return value;}
    private void nameAvailable(String tenant,String name,String except){Long count=jdbc.queryForObject("SELECT COUNT(*) FROM device_groups WHERE tenant_id=? AND name=? AND id<>?",Long.class,tenant,name,except);if(count==null)throw DeviceGroupProjection.unconfirmed();if(count!=0)throw ApiError.conflict("DEVICE_GROUP_NAME_CONFLICT","单位内已存在同名设备分组");}
    private Group locked(String tenant,String id,long expected){
        if(!DeviceGroupProjection.groupId(id)||jdbc.queryForList("SELECT id FROM device_groups WHERE tenant_id=? AND id=? FOR UPDATE",String.class,tenant,id).isEmpty())throw DeviceGroupProjection.missing();
        var current=DeviceGroupQueries.current(jdbc,tenant,id);if(current.revision()!=expected)throw ApiError.conflict("REVISION_CONFLICT","设备分组已更新，请刷新重开");if(current.revision()==DeviceGroupInputs.MAX_SAFE)throw ApiError.conflict("DEVICE_GROUP_REVISION_EXHAUSTED","设备分组版本已达安全上界");return current;
    }
    private void bump(String tenant,String id,long expected){if(jdbc.update("UPDATE device_groups SET revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",tenant,id,expected)!=1)throw ApiError.conflict("REVISION_CONFLICT","设备分组已更新，请刷新重开");}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Group create(AuthService.Identity actor,String tenant,String key,DeviceGroupInputs.Metadata input,String request){
        begin(actor,tenant,key);String op="device-group-create",hash=idem.fingerprint(List.of(tenant,input.name(),input.description()));var replay=replay(actor,tenant,null,op,key,hash);if(replay!=null)return replay;nameAvailable(tenant,input.name(),"");String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO device_groups(id,tenant_id,name,description,revision) VALUES (?,?,?,?,0)",id,tenant,input.name(),input.description());return saved(actor,tenant,id,op,key,hash,request);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Group edit(AuthService.Identity actor,String tenant,String id,String key,DeviceGroupInputs.Metadata input,String request){
        begin(actor,tenant,key);String op="device-group-edit",hash=idem.fingerprint(List.of(tenant,id,input.name(),input.description(),input.expectedRevision()));var replay=replay(actor,tenant,id,op,key,hash);if(replay!=null)return replay;locked(tenant,id,input.expectedRevision());nameAvailable(tenant,input.name(),id);if(jdbc.update("UPDATE device_groups SET name=?,description=?,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",input.name(),input.description(),tenant,id,input.expectedRevision())!=1)throw ApiError.conflict("REVISION_CONFLICT","设备分组已更新，请刷新重开");return saved(actor,tenant,id,op,key,hash,request);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Group membership(AuthService.Identity actor,String tenant,String id,String key,DeviceGroupInputs.Membership input,boolean remove,String request){
        begin(actor,tenant,key);String op=remove?"device-group-remove-device":"device-group-add-device",hash=idem.fingerprint(List.of(tenant,id,input.deviceId(),input.expectedRevision()));if(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND id=? AND archived_at IS NULL",Long.class,tenant,input.deviceId())!=1)throw new ApiError(404,"DEVICE_NOT_FOUND","设备不存在");scope.requireDevice(actor.user().id(),tenant,input.deviceId());var replay=replay(actor,tenant,id,op,key,hash);if(replay!=null)return replay;locked(tenant,id,input.expectedRevision());
        if(!remove&&jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND id=? AND is_simulated=TRUE AND archived_at IS NULL",Long.class,tenant,input.deviceId())!=0)throw new ApiError(404,"DEVICE_NOT_FOUND","设备不存在");
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM device_group_members WHERE tenant_id=? AND group_id=? AND device_id=?",Long.class,tenant,id,input.deviceId());if((remove&&count!=1)||(!remove&&count!=0))throw ApiError.conflict("DEVICE_GROUP_MEMBERSHIP_CONFLICT","设备分组成员关系已变化，请刷新重开");
        int changed=remove?jdbc.update("DELETE FROM device_group_members WHERE tenant_id=? AND group_id=? AND device_id=?",tenant,id,input.deviceId()):jdbc.update("INSERT INTO device_group_members(tenant_id,group_id,device_id) VALUES (?,?,?)",tenant,id,input.deviceId());if(changed!=1)throw ApiError.conflict("DEVICE_GROUP_MEMBERSHIP_CONFLICT","设备分组成员关系已变化，请刷新重开");bump(tenant,id,input.expectedRevision());return saved(actor,tenant,id,op,key,hash,request);
    }
}
