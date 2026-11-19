package com.fireplatform.ingestion;

import com.fireplatform.device.DeviceBindingCatalog;
import com.fireplatform.identity.AuthService;
import com.fireplatform.product.PublishedVersionCatalog;
import com.fireplatform.shared.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceCommands {
    private final JdbcTemplate jdbc; private final IngestionGuard guard; private final SourceQueries queries; private final DeviceBindingCatalog devices; private final PublishedVersionCatalog versions; private final IdempotencyStore idem; private final AuditService audit; private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public SourceCommands(JdbcTemplate jdbc,IngestionGuard guard,SourceQueries queries,DeviceBindingCatalog devices,PublishedVersionCatalog versions,IdempotencyStore idem,AuditService audit,Clock clock) { this.jdbc=jdbc; this.guard=guard; this.queries=queries; this.devices=devices; this.versions=versions; this.idem=idem; this.audit=audit; this.clock=clock; }
    private String token() { byte[] bytes=new byte[32]; random.nextBytes(bytes); return "gih_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void revision(IngestionModels.Source source,long expected) { if(source.revision()!=expected) throw ApiError.conflict("REVISION_CONFLICT","来源配置已更新，请刷新"); }
    private void save(AuthService.Identity actor,String tenant,String op,String key,String hash,Object safeResult,String target,String request) { audit.record(actor.user().id(),tenant,op,target,"SUCCESS",request); idem.save(actor.user().id(),op,key,hash,safeResult); }
    @Transactional public IngestionModels.CredentialResult create(AuthService.Identity actor,String tenant,String key,IngestionInputs.CreateSource in,String request) {
        guard.begin(actor,tenant,"sources:write",key,false); IngestionLimits.text(in.name(),200);
        String hash=idem.fingerprint(List.of(tenant,in.name())); var replay=idem.replay(actor.user().id(),"source-create",key,hash,IngestionModels.Source.class);
        if(replay!=null) return new IngestionModels.CredentialResult(replay,null,true);
        String id=UUID.randomUUID().toString(), token=token();
        jdbc.update("INSERT INTO ingestion_sources(id,tenant_id,name,protocol,is_simulated,enabled,revision,token_hash,token_generation,created_at) VALUES (?,?,?,'GENERIC_HTTP_JSON_V1',FALSE,TRUE,0,?,1,?)",id,tenant,in.name(),Digests.sha256(token),OffsetDateTime.now(clock));
        var source=queries.source(actor.user().id(),tenant,id,false); save(actor,tenant,"source-create",key,hash,source,id,request); return new IngestionModels.CredentialResult(source,token,false);
    }
    @Transactional public IngestionModels.Source update(AuthService.Identity actor,String tenant,String id,String key,IngestionInputs.UpdateSource in,String request) {
        guard.begin(actor,tenant,"sources:write",key,false); var source=queries.source(actor.user().id(),tenant,id,true); IngestionLimits.text(in.name(),200);
        String hash=idem.fingerprint(List.of(tenant,id,in.name(),in.enabled(),in.expectedRevision())); var replay=idem.replay(actor.user().id(),"source-update",key,hash,IngestionModels.Source.class); if(replay!=null) return replay; revision(source,in.expectedRevision());
        jdbc.update("UPDATE ingestion_sources SET name=?,enabled=?,revision=revision+1 WHERE id=?",in.name(),in.enabled(),id);
        var result=queries.source(actor.user().id(),tenant,id,false); save(actor,tenant,"source-update",key,hash,result,id,request); return result;
    }
    @Transactional public IngestionModels.CredentialResult rotate(AuthService.Identity actor,String tenant,String id,String key,IngestionInputs.RotateToken in,String request) {
        guard.begin(actor,tenant,"sources:credentials",key,false); var source=queries.source(actor.user().id(),tenant,id,true);
        String hash=idem.fingerprint(List.of(tenant,id,in.expectedRevision())); var replay=idem.replay(actor.user().id(),"source-rotate",key,hash,IngestionModels.Source.class); if(replay!=null) return new IngestionModels.CredentialResult(replay,null,true); revision(source,in.expectedRevision());
        String token=token(); jdbc.update("UPDATE ingestion_sources SET token_hash=?,token_generation=token_generation+1,revision=revision+1 WHERE id=?",Digests.sha256(token),id);
        var result=queries.source(actor.user().id(),tenant,id,false); save(actor,tenant,"source-rotate",key,hash,result,id,request); return new IngestionModels.CredentialResult(result,token,false);
    }
    @Transactional public IngestionModels.BindingResult bind(AuthService.Identity actor,String tenant,String id,String key,IngestionInputs.Bind in,String request) {
        guard.begin(actor,tenant,"sources:bind",key,true); var source=queries.source(actor.user().id(),tenant,id,true); IngestionLimits.text(in.deviceId(),64); IngestionLimits.text(in.vendorDeviceId(),IngestionLimits.VENDOR_DEVICE_ID); IngestionLimits.text(in.reason(),1000);
        String hash=idem.fingerprint(List.of(tenant,id,in.deviceId(),in.vendorDeviceId(),in.expectedRevision(),in.reason())); var device=devices.lock(actor.user().id(),tenant,in.deviceId()); var replay=idem.replay(actor.user().id(),"source-bind",key,hash,IngestionModels.BindingResult.class); if(replay!=null) return replay; revision(source,in.expectedRevision());
        if(device.simulated()) throw ApiError.conflict("DEVICE_RETIRED_SIMULATION","历史模拟设备不可绑定真实接入来源");
        if(device.productVersionId()==null) throw ApiError.conflict("DEVICE_VERSION_REQUIRED","主源只能绑定已有发布版本的设备"); versions.requireAvailableForNewUse(device.productVersionId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM active_source_bindings WHERE device_id=? OR (source_id=? AND vendor_device_id=?)",Long.class,device.id(),id,in.vendorDeviceId())!=0) throw ApiError.conflict("PRIMARY_SOURCE_CONFLICT","设备或厂商标识已有主源，请先显式解绑");
        String binding=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO source_bindings(id,tenant_id,source_id,device_id,vendor_device_id,created_at,bind_reason,bind_actor_id) VALUES (?,?,?,?,?,?,?,?)",binding,tenant,id,device.id(),in.vendorDeviceId(),OffsetDateTime.now(clock),in.reason(),actor.user().id());
        try { jdbc.update("INSERT INTO active_source_bindings(binding_id,source_id,device_id,vendor_device_id) VALUES (?,?,?,?)",binding,id,device.id(),in.vendorDeviceId()); }
        catch(DuplicateKeyException e) { throw ApiError.conflict("PRIMARY_SOURCE_CONFLICT","主源绑定已变化，请刷新"); }
        jdbc.update("UPDATE ingestion_sources SET revision=revision+1 WHERE id=?",id);
        var result=new IngestionModels.BindingResult(queries.source(actor.user().id(),tenant,id,false),queries.binding(actor.user().id(),tenant,id,binding,false)); save(actor,tenant,"source-bind",key,hash,result,binding,request); return result;
    }
    @Transactional public IngestionModels.BindingResult detach(AuthService.Identity actor,String tenant,String id,String binding,String key,IngestionInputs.Detach in,String request) {
        guard.begin(actor,tenant,"sources:bind",key,true); var source=queries.source(actor.user().id(),tenant,id,true); IngestionLimits.text(in.reason(),1000);
        String hash=idem.fingerprint(List.of(tenant,id,binding,in.expectedRevision(),in.reason())); var before=queries.binding(actor.user().id(),tenant,id,binding,false); devices.lock(actor.user().id(),tenant,before.deviceId()); var replay=idem.replay(actor.user().id(),"source-detach",key,hash,IngestionModels.BindingResult.class); if(replay!=null) return replay; revision(source,in.expectedRevision());
        if(!before.isActive()) throw ApiError.conflict("BINDING_INACTIVE","绑定已结束"); queries.binding(actor.user().id(),tenant,id,binding,true);
        jdbc.update("DELETE FROM active_source_bindings WHERE binding_id=?",binding);
        jdbc.update("UPDATE source_bindings SET ended_at=?,detach_reason=?,detach_actor_id=? WHERE id=?",OffsetDateTime.now(clock),in.reason(),actor.user().id(),binding);
        jdbc.update("UPDATE ingestion_sources SET revision=revision+1 WHERE id=?",id);
        var result=new IngestionModels.BindingResult(queries.source(actor.user().id(),tenant,id,false),queries.binding(actor.user().id(),tenant,id,binding,false)); save(actor,tenant,"source-detach",key,hash,result,binding,request); return result;
    }
}
