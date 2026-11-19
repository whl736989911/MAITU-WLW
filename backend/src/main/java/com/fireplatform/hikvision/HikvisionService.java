package com.fireplatform.hikvision;

import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.CoordinationFence;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HikvisionService {
    public record StreamStatus(String kind,OffsetDateTime lastSuccessAt,String lastError,long total) { }
    public record Status(boolean configured,boolean enabled,OffsetDateTime lastSyncAt,OffsetDateTime lastSuccessAt,
        String lastError,long devicesTotal,long unassignedTotal,long linkedTotal,int pollIntervalSeconds,
        long unassignedEvents,long quarantinedEvents,long mappingErrors,List<StreamStatus> streams) { }
    public record DeviceRow(String externalId,String serial,String name,String model,String sourceDeviceType,
        String sourceStatus,OffsetDateTime sourceUpdatedAt,OffsetDateTime lastObservedAt,Boolean online,
        long sensorCount,String localDeviceId,String localTenantId,String localPlaceId,String assignmentState,long revision) { }
    private static final DateTimeFormatter VENDOR_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    private static final String EVENT_STREAMS="('FAULT','ALARM','MONITOR','HISTORY_MONITOR','DEVICE_EVENT')";
    private final JdbcTemplate jdbc;
    private final HikvisionClient client;
    private final HikvisionIngestion ingestion;
    private final ManagementGuard guard;
    private final CoordinationFence coordination;
    private final DeviceAccessScope access;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final RuntimeConfig runtime;
    private final Clock clock;
    private final HikvisionConfigService configuration;
    private final HikvisionConfigSecrets secrets;
    private final com.fireplatform.deviceservice.DeviceSourceBindingService sourceBindings;
    private final IdempotencyStore idempotency;
    private volatile long lastScheduledPoll;
    public HikvisionService(JdbcTemplate jdbc,HikvisionClient client,HikvisionIngestion ingestion,ManagementGuard guard,
            CoordinationFence coordination,DeviceAccessScope access,AuditService audit,TransactionTemplate transactions,
            RuntimeConfig runtime,Clock clock,HikvisionConfigService configuration,HikvisionConfigSecrets secrets,
            com.fireplatform.deviceservice.DeviceSourceBindingService sourceBindings,IdempotencyStore idempotency) {
        this.jdbc=jdbc;this.client=client;this.ingestion=ingestion;this.guard=guard;this.coordination=coordination;
        this.access=access;this.audit=audit;this.transactions=transactions;this.runtime=runtime;this.clock=clock;this.configuration=configuration;
        this.secrets=secrets;this.sourceBindings=sourceBindings;this.idempotency=idempotency;
    }
    public Status status() {
        HikvisionConfigService.Runtime config=configuration.refreshRuntime();
        String model=config.modelScope();
        var counts=jdbc.queryForMap("SELECT COUNT(*) AS total,COUNT(local_device_id) AS linked FROM hikvision_discovered_devices WHERE model=?",model);
        long total=((Number)counts.get("total")).longValue(),linked=((Number)counts.get("linked")).longValue();
        var state=jdbc.queryForMap("SELECT last_started_at,last_success_at,last_error FROM hikvision_sync_state WHERE source_key='HIKVISION_FIRECLOUD'");
        var streams=jdbc.query("SELECT stream_kind,last_success_at,last_error,record_total FROM hikvision_stream_state ORDER BY stream_kind",
            (r,n)->new StreamStatus(r.getString(1),r.getObject(2,OffsetDateTime.class),r.getString(3),r.getLong(4)));
        return jdbc.queryForObject("""
            SELECT COUNT(CASE WHEN r.apply_error='PENDING_ASSIGNMENT' THEN 1 END),
                COUNT(CASE WHEN r.apply_error IN ('PRE_OWNERSHIP_HISTORY','OWNERSHIP_TIME_UNKNOWN') THEN 1 END),
                COUNT(CASE WHEN r.apply_error IS NOT NULL AND r.apply_error NOT IN ('PENDING_ASSIGNMENT','PRE_OWNERSHIP_HISTORY','OWNERSHIP_TIME_UNKNOWN') THEN 1 END)
            FROM hikvision_raw_records r JOIN hikvision_discovered_devices h ON h.external_id=r.external_device_id
            WHERE h.model=? AND r.stream_kind IN
            """+EVENT_STREAMS,(r,n)->new Status(config.session().configured(),config.enabled(),offset(state.get("last_started_at")),offset(state.get("last_success_at")),
                config.enabled()&&!config.session().configured()?"NOT_CONFIGURED":(String)state.get("last_error"),total,total-linked,linked,config.pollIntervalSeconds(),
                r.getLong(1),r.getLong(2),r.getLong(3),streams),model);
    }
    private static OffsetDateTime offset(Object value){return value==null?null:(OffsetDateTime)value;}
    public Page<DeviceRow> devices(int page,Integer size) {
        var p=Pagination.of(page,size,20);String model=configuration.refreshRuntime().modelScope();
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE model=?",Long.class,model);
        return new Page<>(jdbc.query(deviceSelect()+" WHERE h.model=? ORDER BY h.first_seen_at DESC,h.external_id LIMIT ? OFFSET ?",this::deviceRow,model,p.size(),p.offset()),total,p.page(),p.size());
    }
    private String deviceSelect(){return "SELECT h.*,d.tenant_id,d.place_id,(SELECT COUNT(*) FROM hikvision_discovered_devices s WHERE s.parent_external_id=h.external_id) AS sensor_count FROM hikvision_discovered_devices h LEFT JOIN devices d ON d.id=h.local_device_id";}
    private DeviceRow deviceRow(ResultSet r,int n)throws SQLException {
        String local=r.getString("local_device_id");
        return new DeviceRow(r.getString("external_id"),r.getString("serial"),r.getString("name"),r.getString("model"),r.getString("device_type"),
            r.getString("source_status"),r.getObject("source_updated_at",OffsetDateTime.class),r.getObject("last_observed_at",OffsetDateTime.class),
            null,r.getLong("sensor_count"),local,r.getString("tenant_id"),r.getString("place_id"),local==null?"UNASSIGNED":"LINKED",r.getLong("revision"));
    }
    public Status manualSync(AuthService.Identity actor,String requestId) {
        HikvisionConfigService.Runtime config=configuration.refreshRuntime();
        transactions.executeWithoutResult(s->{guard.begin(actor,"integrations:write",null,"management");audit.record(actor.user().id(),null,"hikvision-sync",null,"REQUESTED",requestId);});
        sync(true,config);return status();
    }
    @Scheduled(fixedDelay=10000,initialDelay=30000,scheduler="hikvisionScheduler")
    public void scheduled() {
        HikvisionConfigService.Runtime config=configuration.refreshRuntime();
        long now=System.currentTimeMillis();
        if(config.enabled()&&config.session().configured()&&(runtime.roles().contains("all")||runtime.roles().contains("ingest"))
                &&now-lastScheduledPoll>=config.pollIntervalSeconds()*1000L) {
            lastScheduledPoll=now;
            sync(false,config);
        }
    }
    private void sync(boolean manual,HikvisionConfigService.Runtime config) {
        if(!config.enabled()||!config.session().configured()){if(manual)throw new ApiError(503,"HIKVISION_NOT_CONFIGURED","海康真实接入未启用或凭证未配置");return;}
        OffsetDateTime now=OffsetDateTime.now(clock);String token=UUID.randomUUID().toString();
        if(jdbc.update("UPDATE hikvision_sync_state SET lease_token=?,lease_until=?,last_started_at=?,last_error=NULL WHERE source_key='HIKVISION_FIRECLOUD' AND (lease_until IS NULL OR lease_until<?)",token,now.plusMinutes(3),now,now)!=1){if(manual)throw ApiError.conflict("HIKVISION_SYNC_BUSY","海康正在同步，请稍后查看结果");return;}
        String failure=null;
        try {
            fetch(HikvisionClient.Stream.UNIT,Map.of("companyID","","unitID",""),token,config);
            fetch(HikvisionClient.Stream.DEVICE,Map.of(),token,config);
            fetch(HikvisionClient.Stream.SENSOR,Map.of(),token,config);
            fetch(HikvisionClient.Stream.STATUS,Map.of(),token,config);
            OffsetDateTime first=jdbc.queryForObject("SELECT MIN(source_created_at) FROM hikvision_discovered_devices",OffsetDateTime.class);
            if(first==null)first=now.minusDays(30);
            for(var kind:List.of(HikvisionClient.Stream.FAULT,HikvisionClient.Stream.ALARM)) {
                String prefix=kind==HikvisionClient.Stream.FAULT?"fault":"alarm";
                OffsetDateTime start=checkpoint(kind,first);
                for(String type:List.of("1","2")) {
                    var filters=new LinkedHashMap<String,String>();filters.put(prefix+"StartTime",vendorTime(start));filters.put(prefix+"StopTime",vendorTime(now));filters.put("timeType",type);
                    fetch(kind,filters,token,config);
                }
                success(kind,now);
            }
            fetch(HikvisionClient.Stream.HISTORY_MONITOR,Map.of("monitorStartTime",vendorTime(checkpoint(HikvisionClient.Stream.HISTORY_MONITOR,first)),"monitorStopTime",vendorTime(now)),token,config);
            success(HikvisionClient.Stream.HISTORY_MONITOR,now);
            // Current source snapshots include older observations; polling never makes them fresh heartbeats.
            fetch(HikvisionClient.Stream.MONITOR,Map.of("monitorStartTime",vendorTime(first),"monitorStopTime",vendorTime(now)),token,config);
            success(HikvisionClient.Stream.MONITOR,now);
            try {
                OffsetDateTime eventStart=checkpoint(HikvisionClient.Stream.DEVICE_EVENT,first);
                fetch(HikvisionClient.Stream.DEVICE_EVENT,Map.of("startTime",vendorTime(eventStart),"stopTime",vendorTime(now)),token,config);
                success(HikvisionClient.Stream.DEVICE_EVENT,now);
            } catch(RuntimeException eventFailure) {
                failure="DEVICE_EVENT:"+(eventFailure instanceof HikvisionClient.Failure f?f.code():"LOCAL_APPLY_FAILED_"+eventFailure.getClass().getSimpleName());
            }
            if(failure==null) jdbc.update("UPDATE hikvision_sync_state SET last_success_at=?,last_error=NULL WHERE source_key='HIKVISION_FIRECLOUD' AND lease_token=?",OffsetDateTime.now(clock),token);
            else jdbc.update("UPDATE hikvision_sync_state SET last_error=? WHERE source_key='HIKVISION_FIRECLOUD' AND lease_token=?",failure,token);
        } catch(Exception e) {
            failure=e instanceof HikvisionClient.Failure f?f.code():"LOCAL_APPLY_FAILED_"+e.getClass().getSimpleName();
            jdbc.update("UPDATE hikvision_sync_state SET last_error=? WHERE source_key='HIKVISION_FIRECLOUD' AND lease_token=?",failure,token);
            org.slf4j.LoggerFactory.getLogger(HikvisionService.class).warn("Hikvision sync failed code={}",failure);
        } finally {
            jdbc.update("UPDATE hikvision_sync_state SET lease_token=NULL,lease_until=NULL WHERE source_key='HIKVISION_FIRECLOUD' AND lease_token=?",token);
        }
        if(manual&&failure!=null)throw new ApiError(503,"HIKVISION_SYNC_FAILED","海康同步未完成："+failure);
    }
    private OffsetDateTime checkpoint(HikvisionClient.Stream kind,OffsetDateTime first) {
        var previous=jdbc.query("SELECT last_success_at FROM hikvision_stream_state WHERE stream_kind=?",(r,n)->r.getObject(1,OffsetDateTime.class),kind.name());
        if(previous.isEmpty()||previous.getFirst()==null)return first;
        OffsetDateTime overlap=previous.getFirst().minusMinutes(15);return overlap.isBefore(first)?first:overlap;
    }
    private static String vendorTime(OffsetDateTime time){return VENDOR_TIME.format(time.withOffsetSameInstant(ZoneOffset.ofHours(8)));}
    private void fetch(HikvisionClient.Stream kind,Map<String,String> filters,String token,HikvisionConfigService.Runtime config) {
        try {
            int page=1;long count=0;String flag="";
            while(true) {
                if(jdbc.update("UPDATE hikvision_sync_state SET lease_until=? WHERE source_key='HIKVISION_FIRECLOUD' AND lease_token=?",OffsetDateTime.now(clock).plusMinutes(3),token)!=1)throw new HikvisionClient.Failure("SYNC_LEASE_LOST");
                var response=config.session().read(kind,page,flag,filters);
                ingestion.accept(kind,response.records(),OffsetDateTime.now(clock),config.modelScope());
                count+=response.records().size();
                if(count>=response.total())break;
                if(response.records().isEmpty()||response.flagId()==null||response.flagId().isBlank()||response.flagId().equals(flag))throw new HikvisionClient.Failure("INCOMPLETE_PAGINATION");
                flag=response.flagId();page++;
            }
            if(kind==HikvisionClient.Stream.UNIT||kind==HikvisionClient.Stream.DEVICE||kind==HikvisionClient.Stream.SENSOR||kind==HikvisionClient.Stream.STATUS)success(kind,OffsetDateTime.now(clock));
        } catch(RuntimeException e) {
            String code=e instanceof HikvisionClient.Failure f?f.code():"LOCAL_APPLY_FAILED_"+e.getClass().getSimpleName();
            if(jdbc.update("UPDATE hikvision_stream_state SET last_error=? WHERE stream_kind=?",code,kind.name())==0)
                jdbc.update("INSERT INTO hikvision_stream_state(stream_kind,last_error) VALUES(?,?)",kind.name(),code);
            throw e;
        }
    }
    private void success(HikvisionClient.Stream kind,OffsetDateTime through) {
        long count=jdbc.queryForObject("SELECT COUNT(DISTINCT external_key) FROM hikvision_raw_records WHERE stream_kind=?",Long.class,kind.name());
        long errors=jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_raw_records WHERE stream_kind=? AND apply_error IS NOT NULL AND apply_error NOT IN ('PENDING_ASSIGNMENT','PRE_OWNERSHIP_HISTORY','OWNERSHIP_TIME_UNKNOWN')",Long.class,kind.name());
        if(jdbc.update("UPDATE hikvision_stream_state SET last_success_at=?,last_error=?,record_total=? WHERE stream_kind=?",through,errors==0?null:"RECORD_MAPPING_ERRORS",count,kind.name())==0)
            jdbc.update("INSERT INTO hikvision_stream_state(stream_kind,last_success_at,last_error,record_total) VALUES(?,?,?,?)",kind.name(),through,errors==0?null:"RECORD_MAPPING_ERRORS",count);
    }
    @Transactional
    public String registerBySerial(AuthService.Identity actor,String tenant,String key,String name,String serial,
            String placeId,String address,String requestId) {
        coordination.lock();
        guard.begin(actor,"integrations:write",tenant,"management");
        guard.requireCurrent(actor,"device:write",tenant,"management");
        access.requireAll(actor.user().id(),tenant);
        return registerBySerialAuthorized(actor,tenant,key,name,serial,placeId,address,requestId);
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String registerAuthorizedBySerial(AuthService.Identity actor,String tenant,String key,String name,String serial,
            String placeId,String address,String requestId) {
        coordination.lock();
        guard.requireCurrent(actor,"integrations:write",tenant,"management");
        guard.requireCurrent(actor,"device:write",tenant,"management");
        access.requireAll(actor.user().id(),tenant);
        return registerBySerialAuthorized(actor,tenant,key,name,serial,placeId,address,requestId);
    }

    private String registerBySerialAuthorized(AuthService.Identity actor,String tenant,String key,String name,String serial,
            String placeId,String address,String requestId) {
        idempotency.validateKey(key);
        if(name==null||name.isBlank()||name.length()>200)throw ApiError.bad("设备名称必填且不能超过200位");
        if(serial==null||!serial.matches("[\\x21-\\x7e]{1,64}"))throw ApiError.bad("设备序列号必须为1至64位安全ASCII标识");
        if(address!=null&&address.length()>500)throw ApiError.bad("设备地址不能超过500位");
        if(placeId==null||placeId.isBlank())throw ApiError.bad("必须选择实际安装位置");
        String hash=idempotency.fingerprint(List.of(tenant,name,serial,placeId,address==null?"":address));
        String replay=idempotency.replay(actor.user().id(),"hikvision-register-by-serial",key,hash,String.class);
        if(replay!=null)return replay;
        HikvisionConfigService.Runtime config=configuration.refreshRuntime();
        var places=jdbc.queryForList("SELECT name FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",String.class,placeId,tenant);
        if(places.isEmpty())throw ApiError.bad("位置不属于所选单位或已归档");
        var rows=jdbc.queryForList("SELECT * FROM hikvision_discovered_devices WHERE serial=? AND model=? AND resource_kind='DEVICE' FOR UPDATE",serial,config.modelScope());
        if(rows.isEmpty())throw ApiError.conflict("HIKVISION_DEVICE_NOT_DISCOVERED","未发现该型号范围内的精确序列号设备，请先同步海康来源");
        if(rows.size()!=1)throw ApiError.conflict("HIKVISION_DEVICE_AMBIGUOUS","该型号范围内序列号匹配多个来源资源，拒绝自动关联");
        var row=rows.getFirst();
        String external=(String)row.get("external_id");
        if(row.get("local_device_id")!=null)throw ApiError.conflict("HIKVISION_DEVICE_ALREADY_ASSIGNED","来源设备已有本地归属；不能重复建档或覆盖");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=?",Long.class,serial)>0)
            throw ApiError.conflict("DEVICE_EUI_CONFLICT","已有历史本地资产使用相同设备标识，拒绝重复建档");
        OffsetDateTime ownershipFrom=OffsetDateTime.now(clock);
        String device=UUID.randomUUID().toString();
        String model=(String)row.get("model");
        jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,place_id,physical_state,is_simulated,revision,integration_state,ownership_from) VALUES(?,?,?,?,?,?,?,'UNKNOWN',FALSE,0,'PENDING_INTEGRATION',?)",
            device,tenant,name,serial,model==null?"":model,address==null?"":address,placeId,ownershipFrom);
        jdbc.update("INSERT INTO device_place_history(id,device_id,tenant_id,action,to_place_id,to_place_name,revision_after,actor_id,occurred_at) VALUES(?,?,?,'REGISTER',?,?,0,?,?)",
            UUID.randomUUID().toString(),device,tenant,placeId,places.getFirst(),actor.user().id(),ownershipFrom);
        assignSource(actor,tenant,external,row,device,ownershipFrom,config,"hikvision-register-by-serial",requestId);
        idempotency.save(actor.user().id(),"hikvision-register-by-serial",key,hash,device);
        return device;
    }

    @Transactional
    public DeviceRow assign(AuthService.Identity actor,String external,HikvisionController.Assignment input,String requestId) {
        HikvisionConfigService.Runtime config=configuration.refreshRuntime();
        coordination.lock();guard.begin(actor,"integrations:write",input.tenantId(),"management");
        guard.requireCurrent(actor,"device:write",input.tenantId(),"management");access.requireAll(actor.user().id(),input.tenantId());
        var rows=jdbc.queryForList("SELECT * FROM hikvision_discovered_devices WHERE external_id=? FOR UPDATE",external);
        if(rows.isEmpty())throw ApiError.missing();var row=rows.getFirst();
        if(!config.modelScope().equals(row.get("model")))throw ApiError.missing();
        if(((Number)row.get("revision")).longValue()!=input.expectedRevision())throw ApiError.conflict("REVISION_CONFLICT","来源设备已更新，请刷新");
        if(row.get("local_device_id")!=null)throw ApiError.conflict("HIKVISION_DEVICE_ALREADY_ASSIGNED","来源设备已有本地归属；请通过设备归属移交操作变更，不能覆盖现有关联");
        String place=input.placeId();if(place!=null&&place.isBlank())place=null;
        String placeName=null;
        if(place!=null){var places=jdbc.queryForList("SELECT name FROM places WHERE id=? AND tenant_id=? AND archived_at IS NULL",String.class,place,input.tenantId());if(places.isEmpty())throw ApiError.bad("位置不属于所选单位或已归档");placeName=places.getFirst();}
        String device=input.existingDeviceId();
        java.time.OffsetDateTime ownershipFrom;
        if(device!=null&&!device.isBlank()) {
            access.requireDevice(actor.user().id(),input.tenantId(),device);
            var existing=jdbc.query("SELECT ownership_from FROM devices WHERE id=? AND tenant_id=? AND is_simulated=FALSE AND archived_at IS NULL",(rs,n)->rs.getObject(1,java.time.OffsetDateTime.class),device,input.tenantId());
            if(existing.isEmpty())throw ApiError.missing();
            ownershipFrom=existing.getFirst();
            if(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_discovered_devices WHERE local_device_id=?",Long.class,device)>0)throw ApiError.conflict("HIKVISION_DEVICE_ALREADY_LINKED","本地设备已关联其他来源资源");
            if(place!=null)throw ApiError.bad("绑定已有设备不修改位置，请在设备管理中调整");
        } else {
            String eui=(String)row.get("serial");if(eui==null||eui.isBlank())eui=external;
            if(eui.length()>64)throw ApiError.bad("来源标识过长，请绑定已有本地设备");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM devices WHERE device_eui=? AND identity_slot='ACTIVE'",Long.class,eui)>0)throw ApiError.conflict("DEVICE_EUI_CONFLICT","已有相同标识设备，请明确选择已有本地设备");
            String name=(String)row.get("name"),model=(String)row.get("model");if(name.length()>200||(model!=null&&model.length()>100))throw ApiError.bad("来源设备名称或型号超过本地限制，请绑定已有设备");
            device=UUID.randomUUID().toString();
            ownershipFrom=OffsetDateTime.now(clock);
            jdbc.update("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,place_id,physical_state,is_simulated,revision,integration_state,ownership_from) VALUES(?,?,?,?,?,?,?,'UNKNOWN',FALSE,0,'PENDING_INTEGRATION',?)",device,input.tenantId(),name,eui,model==null?"":model,placeName==null?"":placeName,place,ownershipFrom);
            if(place!=null)jdbc.update("INSERT INTO device_place_history(id,device_id,tenant_id,action,to_place_id,to_place_name,revision_after,actor_id,occurred_at) VALUES(?,?,?,'REGISTER',?,?,0,?,?)",UUID.randomUUID().toString(),device,input.tenantId(),place,placeName,actor.user().id(),OffsetDateTime.now(clock));
        }
        // Observed facts before local ownership remain quarantined by the ingestion cutoff.
        assignSource(actor,input.tenantId(),external,row,device,ownershipFrom,config,"hikvision-assign",requestId);
        return jdbc.queryForObject(deviceSelect()+" WHERE h.external_id=?",this::deviceRow,external);
    }

    private void assignSource(AuthService.Identity actor,String tenant,String external,Map<String,Object> row,
            String device,OffsetDateTime ownershipFrom,HikvisionConfigService.Runtime config,String operation,String requestId) {
        if(ownershipFrom==null) {
            jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=?,revision=revision+1 WHERE external_id=?",device,external);
        } else {
            jdbc.update("UPDATE hikvision_discovered_devices SET local_device_id=?,event_history_from=CASE WHEN event_history_from IS NULL OR event_history_from<? THEN ? ELSE event_history_from END,revision=revision+1 WHERE external_id=?",device,ownershipFrom,ownershipFrom,external);
        }
        String serial=(String)row.get("serial");if(serial==null||serial.isBlank())serial=external;
        sourceBindings.bind(tenant,device,"HIKVISION_FIRECLOUD",external,serial,accountFingerprint(config),actor.user().id());
        audit.record(actor.user().id(),tenant,operation,device,"SUCCESS",requestId);
        ingestion.replay(external,OffsetDateTime.now(clock));
    }

    private String accountFingerprint(HikvisionConfigService.Runtime config) {
        try { return config.session().configured()?secrets.fingerprint(config.endpoint()+"\n"+config.appKey()):null; }
        catch(RuntimeException unavailable) { return null; }
    }
}
