package com.fireplatform.device;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Create local assets for direct gateway/operator HTTP and MQTT reports; no legacy platform calls. */
@Service
public class LegacyRealtimeDeviceService {
    public record LegacyProfile(String id,String name,String manufacturer,String model,String legacyProductId,boolean decoderReady) { }
    public record Created(String id) { }
    public record AepOnboarding(long revision,String imei,List<String> upstreamIds) { }
    private record AepTarget(long revision,String imei,String product,OffsetDateTime ownershipFrom) {
        boolean eligible() { return imei!=null && imei.matches("[0-9]{15}") && ("223".equals(product) || "323".equals(product) || "500".equals(product)); }
    }
    private record AepEvidence(String id,OffsetDateTime observedAt,String imei) { }
    private static final String AEP_ASSOCIATE = "legacy-aep-device-associate";
    private record Profile(String id,String productId,String name,String model,long version,String legacyProductId) { }
    private static final String PROFILES = " FROM product_versions v JOIN products p ON p.id=v.product_id WHERE v.schema_format='LEGACY_ATTR_JS_V1' AND v.legacy_product_id IS NOT NULL AND p.state='ACTIVE'";
    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final CoordinationFence coordination;
    private final DeviceAccessScope scopes;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final PageSizePolicy sizes;
    private final Clock clock;

    public LegacyRealtimeDeviceService(JdbcTemplate jdbc,ManagementGuard guard,CoordinationFence coordination,
            DeviceAccessScope scopes,IdempotencyStore idem,AuditService audit,PageSizePolicy sizes,Clock clock) {
        this.jdbc=jdbc;this.guard=guard;this.coordination=coordination;this.scopes=scopes;
        this.idem=idem;this.audit=audit;this.sizes=sizes;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Page<LegacyProfile> profiles(int page,Integer size,String keyword) {
        var pagination=Pagination.of(page,size,sizes.defaultPageSize());
        String term=keyword==null?"":keyword;
        if(term.length()>Limits.MAX_KEYWORD_LENGTH)throw ApiError.bad("搜索关键字过长");
        String like="%"+term.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String where=PROFILES+" AND (LOWER(p.name) LIKE ? ESCAPE '!' OR LOWER(p.manufacturer) LIKE ? ESCAPE '!' OR LOWER(p.model) LIKE ? ESCAPE '!' OR LOWER(v.legacy_product_id) LIKE ? ESCAPE '!')";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,like,like,like,like);
        var items=jdbc.query("SELECT v.id,p.name,p.manufacturer,p.model,v.legacy_product_id,EXISTS(SELECT 1 FROM legacy_product_decoders c WHERE c.legacy_product_id=v.legacy_product_id) AS decoder_ready"+where+" ORDER BY p.name,p.model,v.legacy_product_id,v.id LIMIT ? OFFSET ?",
            (row,n)->new LegacyProfile(row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5),row.getBoolean(6)),like,like,like,like,pagination.size(),pagination.offset());
        return new Page<>(items,total,pagination.page(),pagination.size());
    }

    List<WorkbookModel> workbookModels() {
        return jdbc.query("SELECT v.id,p.name,p.manufacturer,p.model,v.legacy_product_id,v.version_number"+PROFILES,
            (row,n)->new WorkbookModel(row.getString(1),row.getString(2)+" · "+row.getString(3)+" / "+row.getString(4)
                +"（产品 "+row.getString(5)+"，解析版本 "+row.getLong(6)+"）"));
    }
    record WorkbookModel(String id,String label) { }

    String terminalProfile(String exactProductName,String selectedProfile) {
        var candidates=jdbc.queryForList("SELECT v.id"+PROFILES+" AND p.name=? ORDER BY v.id",String.class,exactProductName);
        if(candidates.size()==1)return candidates.getFirst();
        if(candidates.isEmpty())throw ApiError.bad("未精确匹配本地型号；请填写旧产品全名或先维护解析定义");
        if(selectedProfile!=null&&candidates.contains(selectedProfile))return selectedProfile;
        throw ApiError.bad("同名产品对应多个解析定义，请核对协议后选择上方的共用型号");
    }

    void requireNewTerminalIdentity(String identifier) {
        var identity=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM devices WHERE device_eui=? AND archived_at IS NULL
                          UNION ALL SELECT 1 FROM device_aliases a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id WHERE a.alias_value=? AND d.archived_at IS NULL),
                   EXISTS(SELECT 1 FROM devices WHERE device_eui=? UNION ALL SELECT 1 FROM device_aliases WHERE alias_value=?)
            """,(rs,n)->rs.getBoolean(1)?"ACTIVE":rs.getBoolean(2)?"HISTORY":"NEW",identifier,identifier,identifier,identifier);
        if("ACTIVE".equals(identity))throw ApiError.conflict("DEVICE_EUI_CONFLICT","设备标识已被占用，不批量覆盖");
        if("HISTORY".equals(identity))
            throw ApiError.conflict("TERMINAL_IDENTIFICATION_HAS_HISTORY","设备标识有归档历史，请逐台核对重新建档，不批量覆盖");
    }

    @Transactional
    public String register(AuthService.Identity actor,String tenant,String key,VendorDeviceInputs.Register input,String requestId) {
        coordination.lock();
        guard.begin(actor,"device:write",tenant,"management");
        return registerCurrent(actor,tenant,key,input,requestId);
    }

    /** Internal batch entry: the surrounding transaction has already authorized the current operator session. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String registerAuthorized(AuthService.Identity actor,String tenant,String key,VendorDeviceInputs.Register input,String requestId) {
        coordination.lock();
        guard.requireCurrent(actor,"device:write",tenant,"management");
        return registerCurrent(actor,tenant,key,input,requestId);
    }

    private String registerCurrent(AuthService.Identity actor,String tenant,String key,VendorDeviceInputs.Register input,String requestId) {
        scopes.requireAll(actor.user().id(),tenant);
        idem.validateKey(key);
        if(!"LEGACY_REALTIME".equals(input.source()))throw ApiError.bad("旧实时接口登记来源不一致");
        if(input.legacyProfileId()==null || input.legacyProfileId().isBlank())throw ApiError.bad("请选择本地设备型号/协议定义，无需旧平台设备记录或通用物模型发布版本");
        String address=input.address()==null?"":input.address().trim();
        String upstream=input.upstreamDeviceId()==null?input.serial():input.upstreamDeviceId();
        String hash=idem.fingerprint(List.of(tenant,input.source(),input.name(),input.serial(),input.placeId(),address,input.legacyProfileId(),upstream));
        var replay=idem.replay(actor.user().id(),"legacy-realtime-device-register",key,hash,Created.class);
        if(replay!=null)return replay.id();
        var places=jdbc.queryForList("SELECT name FROM places WHERE tenant_id=? AND id=? AND archived_at IS NULL",String.class,tenant,input.placeId());
        if(places.isEmpty())throw ApiError.bad("安装位置不存在、不属于当前单位或已归档");
        var profiles=jdbc.query("SELECT v.id,v.product_id,p.name,p.model,v.version_number,v.legacy_product_id"+PROFILES+" AND v.id=?",
            (row,n)->new Profile(row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getLong(5),row.getString(6)),input.legacyProfileId());
        if(profiles.isEmpty())throw ApiError.bad("所选设备型号/协议定义不存在或已停用，不能用无解析定义的普通物模型草稿代替");
        boolean previouslyOwned=requireUnclaimed(input.serial());
        if(!upstream.equals(input.serial()))previouslyOwned=requireUnclaimed(upstream)||previouslyOwned;
        var profile=profiles.getFirst();
        String id=UUID.randomUUID().toString();
        OffsetDateTime now=OffsetDateTime.now(clock);
        // First registration accepts untimed native reports; re-registration must isolate the former lifecycle.
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,place_id,physical_state,is_simulated,product_id,product_version_id,product_name,version_number,revision,integration_state,ownership_from) VALUES (?,?,?,?,?,?,?,'UNKNOWN',FALSE,?,?,?,?,0,'PENDING_INTEGRATION',?)",
            id,tenant,input.name().trim(),input.serial(),profile.model(),address,input.placeId(),profile.productId(),profile.id(),profile.name(),profile.version(),previouslyOwned?now:null);
        String previousSerialOwner=alias(tenant,id,"EUI",input.serial(),now);
        String previousUpstreamOwner=!upstream.equals(input.serial())?alias(tenant,id,"VENDOR_ID",upstream,now):null;
        jdbc.update("INSERT INTO device_place_history(id,device_id,tenant_id,action,to_place_id,to_place_name,revision_after,actor_id,occurred_at) VALUES (?,?,?,'REGISTER',?,?,0,?,?)",
            UUID.randomUUID().toString(),id,tenant,input.placeId(),places.getFirst(),actor.user().id(),now);
        jdbc.update("INSERT INTO device_version_history(id,device_id,tenant_id,from_version_id,to_version_id,reason,actor_id,occurred_at) VALUES (?,?,?,NULL,?,'REGISTER',?,?)",
            UUID.randomUUID().toString(),id,tenant,profile.id(),actor.user().id(),now);
        audit.recordReason(actor.user().id(),tenant,"LEGACY_REALTIME_DEVICE_REGISTER",id,"SUCCESS",requestId,
            "本地新增直连设备，不访问旧业务平台；协议定义="+profile.legacyProductId()
            +(previousSerialOwner==null?"":"；重新建档，EUI关联原归档资产="+previousSerialOwner)
            +(previousUpstreamOwner==null?"":"；上报标识关联原归档资产="+previousUpstreamOwner));
        idem.save(actor.user().id(),"legacy-realtime-device-register",key,hash,new Created(id));
        return id;
    }

    @Transactional(readOnly=true)
    public AepOnboarding aepOnboarding(AuthService.Identity actor,String tenant,String id) {
        scopes.requireAll(actor.user().id(),tenant);
        return aepOnboarding(tenant,id,aepTarget(tenant,id));
    }

    @Transactional
    public AepOnboarding confirmAep(AuthService.Identity actor,String tenant,String id,String key,VendorDeviceInputs.ConfirmAep input,String requestId) {
        coordination.lock();
        guard.begin(actor,"device:write",tenant,"management");
        guard.requireCurrent(actor,"integrations:write",tenant,"management");
        scopes.requireAll(actor.user().id(),tenant);
        idem.validateKey(key);
        var target=aepTarget(tenant,id);
        String upstream=input.upstreamDeviceId();
        String fingerprint=idem.fingerprint(List.of(tenant,id,upstream,input.expectedRevision()));
        var replay=idem.replay(actor.user().id(),AEP_ASSOCIATE,key,fingerprint,AepOnboarding.class);
        if(replay!=null)return replay;
        if(!target.eligible())throw ApiError.bad("仅支持已登记为 AEP 协议 223、323 或 500 且设备标识为 IMEI 的烟感");
        if(target.revision()!=input.expectedRevision())throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新后重新核对");
        if(requireUnclaimed(upstream))throw ApiError.conflict("AEP_ID_HAS_HISTORY","该上报标识有历史归属，不能通过首次关联操作改绑");
        OffsetDateTime now=OffsetDateTime.now(clock);
        var reports=jdbc.query("""
            SELECT id,observed_at,aep_reported_imei FROM legacy_ingest_messages
            WHERE vendor_platform='aep_turbo' AND vendor_device_id=? AND ingress='HTTP_NOTIFY'
              AND received_at>=? AND received_at<=? AND device_id IS NULL AND tenant_id IS NULL AND failure_code='UNKNOWN_DEVICE'
              AND aep_reported_imei IS NOT NULL
            ORDER BY received_at DESC,id DESC LIMIT 1
            """,(r,n)->new AepEvidence(r.getString(1),r.getObject(2,OffsetDateTime.class),r.getString(3)),
            upstream,now.minusMinutes(15),now);
        if(reports.isEmpty() || !matchesAepEvidence(reports.getFirst(),target,now))
            throw ApiError.bad("最近 15 分钟未找到与本设备 IMEI 匹配的 AEP 上报，请核对 deviceId 后让设备重新上报");
        if(jdbc.update("UPDATE devices SET revision=revision+1 WHERE id=? AND tenant_id=? AND archived_at IS NULL AND revision=?",
            id,tenant,target.revision())!=1)throw ApiError.conflict("REVISION_CONFLICT","设备已更新，请刷新后重新核对");
        jdbc.update("""
            INSERT INTO device_aliases(id,tenant_id,device_id,kind,alias_value,origin,created_at,verified_platform)
            VALUES (?,?,?,'VENDOR_ID',?,'MANUAL',?,'aep_turbo')
            """,UUID.randomUUID().toString(),tenant,id,upstream,now);
        audit.recordReason(actor.user().id(),tenant,"LEGACY_AEP_DEVICE_ASSOCIATE",id,"SUCCESS",requestId,
            "核对 AEP 上报标识="+upstream+"；证据报文="+reports.getFirst().id()+"；仅接收关联后的新上报");
        var result=aepOnboarding(tenant,id,aepTarget(tenant,id));
        idem.save(actor.user().id(),AEP_ASSOCIATE,key,fingerprint,result);
        return result;
    }

    private AepTarget aepTarget(String tenant,String id) {
        var rows=jdbc.query("""
            SELECT d.revision,d.device_eui,pv.legacy_product_id,d.ownership_from FROM devices d
            LEFT JOIN product_versions pv ON pv.id=d.product_version_id AND pv.schema_format='LEGACY_ATTR_JS_V1'
            WHERE d.tenant_id=? AND d.id=? AND d.archived_at IS NULL AND d.is_simulated=FALSE
            """,(r,n)->new AepTarget(r.getLong(1),r.getString(2),r.getString(3),r.getObject(4,OffsetDateTime.class)),tenant,id);
        if(rows.isEmpty())throw ApiError.missing();
        return rows.getFirst();
    }

    private AepOnboarding aepOnboarding(String tenant,String id,AepTarget target) {
        if(!target.eligible())return new AepOnboarding(target.revision(),null,List.of());
        var ids=jdbc.queryForList("""
            SELECT alias_value FROM device_aliases WHERE tenant_id=? AND device_id=? AND verified_platform='aep_turbo'
            ORDER BY created_at,alias_value
            """,String.class,tenant,id);
        return new AepOnboarding(target.revision(),target.imei(),ids);
    }

    private boolean matchesAepEvidence(AepEvidence evidence,AepTarget target,OffsetDateTime now) {
        var at=evidence.observedAt();
        return target.imei().equals(evidence.imei()) && at!=null && !at.isBefore(now.minusMinutes(15)) && !at.isAfter(now)
            && (target.ownershipFrom()==null || !at.isBefore(target.ownershipFrom()));
    }

    private boolean requireUnclaimed(String identifier) {
        // Historical vendor routing also belongs to the current owner of the same physical device.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE device_eui=? AND archived_at IS NULL) OR EXISTS(SELECT 1 FROM device_aliases a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id WHERE a.alias_value=? AND (d.archived_at IS NULL OR a.verified_platform IS NOT NULL)) OR EXISTS(SELECT 1 FROM device_aliases a JOIN devices old ON old.id=a.device_id AND old.tenant_id=a.tenant_id JOIN devices d ON d.device_eui=old.device_eui JOIN product_versions pv ON pv.id=d.product_version_id WHERE a.alias_value=? AND old.archived_at IS NOT NULL AND d.archived_at IS NULL AND d.ownership_from IS NOT NULL AND pv.schema_format='LEGACY_ATTR_JS_V1')",Boolean.class,identifier,identifier,identifier)))
            throw ApiError.conflict("DEVICE_EUI_CONFLICT","该标识已被设备占用或有受保护的接入关联，不能重复使用");
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM devices WHERE device_eui=?) OR EXISTS(SELECT 1 FROM device_aliases WHERE alias_value=?)",Boolean.class,identifier,identifier));
    }

    /** Alias rows are current routing slots; immutable messages, alarms and the old asset keep their original IDs. */
    private String alias(String tenant,String device,String kind,String value,OffsetDateTime at) {
        var previous=jdbc.queryForList("SELECT a.id,a.device_id FROM device_aliases a JOIN devices d ON d.id=a.device_id AND d.tenant_id=a.tenant_id WHERE a.tenant_id=? AND a.kind=? AND a.alias_value=? AND d.archived_at IS NOT NULL FOR UPDATE",tenant,kind,value);
        if(!previous.isEmpty()) {
            var old=previous.getFirst();
            jdbc.update("UPDATE device_aliases SET device_id=?,origin='MANUAL',created_at=? WHERE id=?",device,at,old.get("id"));
            return (String)old.get("device_id");
        }
        jdbc.update("INSERT INTO device_aliases(id,tenant_id,device_id,kind,alias_value,origin,created_at) VALUES (?,?,?,?,?,'MANUAL',?)",
            UUID.randomUUID().toString(),tenant,device,kind,value,at);
        return null;
    }
}
