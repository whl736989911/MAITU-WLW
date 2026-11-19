package com.fireplatform.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** N11：可解释画像。规则版本 + 观测窗口 + 不可变快照；不改报警、不停设备。 */
@Service
public class DeviceProfileService {
    public record Rule(String id,String tenantId,int version,int windowDays,int minSamples,boolean enabled,String reason,OffsetDateTime createdAt) { }
    public record RulePage(List<Rule> items) { }
    public record Snapshot(String id,String deviceId,String ruleId,int ruleVersion,int windowDays,int minSamples,String status,int sampleCount,BigDecimal latestPercent,BigDecimal minPercent,BigDecimal maxPercent,BigDecimal meanPercent,OffsetDateTime windowStart,OffsetDateTime windowEnd,OffsetDateTime computedAt,String reason) { }
    private final JdbcTemplate jdbc; private final DatabaseTime time; private final ExecutionAuthorization auth; private final TenantFence tenants; private final DeviceQueryService devices; private final ObjectMapper json; private final ScopeFilter scopeFilter;
    public DeviceProfileService(JdbcTemplate jdbc,DatabaseTime time,ExecutionAuthorization auth,TenantFence tenants,DeviceQueryService devices,ObjectMapper json,ScopeFilter scopeFilter) { this.jdbc=jdbc; this.time=time; this.auth=auth; this.tenants=tenants; this.devices=devices; this.json=json; this.scopeFilter=scopeFilter; }

    public Rule currentRule(String tenant) {
        var rows=jdbc.query("SELECT id,tenant_id,version,window_days,min_samples,enabled,reason,created_at FROM profile_rules WHERE tenant_id=? ORDER BY version DESC LIMIT 1",(rs,n)->new Rule(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getBoolean(6),rs.getString(7),rs.getObject(8,OffsetDateTime.class)),tenant);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional(readOnly=true) public RulePage currentRules(String actor,String tenant,ScopeFilter.Selection selection) {
        var scope=scopeFilter.units(actor,"pr","tenant_id",tenant,selection);
        String where=" WHERE NOT EXISTS (SELECT 1 FROM profile_rules newer WHERE newer.tenant_id=pr.tenant_id AND newer.version>pr.version)"+scope.and();
        var rules=jdbc.query("SELECT pr.id,pr.tenant_id,pr.version,pr.window_days,pr.min_samples,pr.enabled,pr.reason,pr.created_at FROM profile_rules pr"+where+" ORDER BY pr.tenant_id",(rs,n)->new Rule(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getBoolean(6),rs.getString(7),rs.getObject(8,OffsetDateTime.class)),scope.bindings().toArray());
        return new RulePage(List.copyOf(rules));
    }
    private void requireVisibleDevice(String actor,String tenant,String deviceId,ScopeFilter.Selection selection) {
        devices.device(actor,tenant,deviceId,false);
        var scope=scopeFilter.devices(actor,"d",tenant,selection);
        var args=new ArrayList<Object>();args.add(tenant);args.add(deviceId);args.addAll(scope.bindings());
        if(jdbc.query("SELECT d.id FROM devices d WHERE d.tenant_id=? AND d.id=? AND d.is_simulated=FALSE AND d.archived_at IS NULL"+scope.and(),(rs,n)->rs.getString(1),args.toArray()).isEmpty()) throw ApiError.missing();
    }
    @Transactional public Rule configure(AuthService.Identity actor,String tenant,int windowDays,int minSamples,boolean enabled,String reason) {
        auth.lockRequest(actor,tenant,List.of("profiles:write")); tenants.lockEnabled(tenant);
        if(windowDays<1 || windowDays>365) throw ApiError.bad("窗口天数须在1..365");
        if(minSamples<1 || minSamples>1000) throw ApiError.bad("最小样本数须在1..1000");
        if(reason==null || reason.trim().isEmpty() || reason.length()>1000) throw ApiError.bad("必须说明规则变更原因");
        var current=currentRule(tenant); int version=current==null?1:current.version()+1; var now=time.now();
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO profile_rules(id,tenant_id,version,window_days,min_samples,enabled,created_by,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?)",id,tenant,version,windowDays,minSamples,enabled,actor.user().id(),reason.trim(),now);
        return currentRule(tenant);
    }

    @Transactional public Snapshot recompute(AuthService.Identity actor,String tenant,String deviceId,String reason) {
        auth.lockRequest(actor,tenant,List.of("profiles:write")); tenants.lockEnabled(tenant);
        if(reason==null || reason.trim().isEmpty() || reason.length()>1000) throw ApiError.bad("必须说明重算原因");
        var device=devices.device(actor.user().id(),tenant,deviceId,false);
        var rule=currentRule(tenant);
        if(rule==null || !rule.enabled()) throw ApiError.conflict("PROFILE_RULE_NOT_CONFIGURED","单位尚未配置启用的画像规则");
        var now=time.now(); var start=now.minusDays(rule.windowDays());
        var samples=jdbc.queryForList("SELECT battery_percent FROM real_device_battery_observations WHERE tenant_id=? AND device_id=? AND observed_at>=? AND observed_at<=? ORDER BY observed_at,id",BigDecimal.class,tenant,deviceId,start,now);
        String capability=device.productVersionId()==null&&!samples.isEmpty()?"SUPPORTED":batteryCapability(device.productVersionId());
        String status; BigDecimal latest=null,min=null,max=null,mean=null;
        if(!"SUPPORTED".equals(capability)) status="UNSUPPORTED";
        else if(samples.isEmpty()) status="MISSING";
        else if(samples.size()<rule.minSamples()) status="INSUFFICIENT";
        else {
            status="OK"; latest=samples.getLast(); min=Collections.min(samples); max=Collections.max(samples);
            BigDecimal sum=BigDecimal.ZERO; for(BigDecimal v:samples) sum=sum.add(v);
            mean=sum.divide(BigDecimal.valueOf(samples.size()),36,RoundingMode.HALF_UP);
        }
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_profile_snapshots(id,tenant_id,device_id,rule_id,rule_version,window_days,min_samples,status,sample_count,latest_percent,min_percent,max_percent,mean_percent,window_start,window_end,computed_at,actor_id,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,tenant,deviceId,rule.id(),rule.version(),rule.windowDays(),rule.minSamples(),status,samples.size(),latest,min,max,mean,start,now,now,actor.user().id(),reason.trim());
        return latestSnapshot(tenant,deviceId);
    }

    private String batteryCapability(String versionId) {
        if(versionId==null) return "PENDING";
        var rows=jdbc.queryForList("SELECT capabilities_json FROM product_versions WHERE id=?",String.class,versionId);
        if(rows.isEmpty()) return "PENDING";
        try { JsonNode node=json.readTree(rows.getFirst()); return node.path("battery").asText("PENDING"); }
        catch(Exception e) { return "PENDING"; }
    }

    public Snapshot latestSnapshot(String actor,String tenant,String deviceId,ScopeFilter.Selection selection) { requireVisibleDevice(actor,tenant,deviceId,selection); return latestSnapshot(tenant,deviceId); }
    public List<Snapshot> history(String actor,String tenant,String deviceId,int limit,ScopeFilter.Selection selection) { requireVisibleDevice(actor,tenant,deviceId,selection); return history(tenant,deviceId,limit); }
    public Snapshot latestSnapshot(String tenant,String deviceId) {
        var rows=jdbc.query("SELECT id,device_id,rule_id,rule_version,window_days,min_samples,status,sample_count,latest_percent,min_percent,max_percent,mean_percent,window_start,window_end,computed_at,reason FROM device_profile_snapshots WHERE tenant_id=? AND device_id=? ORDER BY computed_at DESC,id LIMIT 1",(rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getString(7),rs.getInt(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getBigDecimal(12),rs.getObject(13,OffsetDateTime.class),rs.getObject(14,OffsetDateTime.class),rs.getObject(15,OffsetDateTime.class),rs.getString(16)),tenant,deviceId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    public List<Snapshot> history(String tenant,String deviceId,int limit) {
        return jdbc.query("SELECT id,device_id,rule_id,rule_version,window_days,min_samples,status,sample_count,latest_percent,min_percent,max_percent,mean_percent,window_start,window_end,computed_at,reason FROM device_profile_snapshots WHERE tenant_id=? AND device_id=? ORDER BY computed_at DESC,id LIMIT ?",(rs,n)->new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getString(7),rs.getInt(8),rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getBigDecimal(12),rs.getObject(13,OffsetDateTime.class),rs.getObject(14,OffsetDateTime.class),rs.getObject(15,OffsetDateTime.class),rs.getString(16)),tenant,deviceId,Math.min(Math.max(limit,1),50));
    }
}
