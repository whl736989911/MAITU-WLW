package com.fireplatform.device;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ExecutionAuthorization;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Evidence-backed operational portrait, independent of immutable battery percentage snapshots. */
@Service
public class OperationalPortraitService {
    public static final List<String> LABELS=List.of("FREQUENT_FALSE_ALARM","FREQUENT_FAULT","FREQUENT_OFFLINE","WEAK_SIGNAL","LOW_BATTERY","LONG_OFFLINE");
    public record Rule(String id,String tenantId,int version,int windowDays,int falseAlarmCount,int faultCount,int offlineCount,BigDecimal weakSignalPercent,String weakSignalMetric,BigDecimal weakSignalThreshold,BigDecimal lowBatteryPercent,int longOfflineDays,List<String> selectedLabels,boolean enabled,String reason,OffsetDateTime createdAt) { }
    public record RulePage(List<Rule> items) { }
    public record LabelResult(String label,String status,boolean matched,int sampleCount,String value,String evidence,OffsetDateTime latestAt) { }
    public record Snapshot(String id,String tenantId,String deviceId,String ruleId,int ruleVersion,OffsetDateTime windowStart,OffsetDateTime windowEnd,OffsetDateTime evaluatedAt,List<LabelResult> labels,String reason) { }
    public record DeviceSummary(String id,String tenantId,String name,String address,String placeId,OffsetDateTime lastMessageAt,String productVersionId,boolean simulated) { }
    public record Page(List<DeviceSummary> items,long total,int page,int size,Map<String,Long> labelCounts) { }
    public record Configure(int expectedRuleVersion,int windowDays,int falseAlarmCount,int faultCount,int offlineCount,BigDecimal weakSignalPercent,String weakSignalMetric,BigDecimal weakSignalThreshold,BigDecimal lowBatteryPercent,int longOfflineDays,List<String> selectedLabels,boolean enabled,String reason) { }
    public record Evaluate(int expectedRuleVersion,String reason) { }
    private final JdbcTemplate jdbc; private final DatabaseTime time; private final ExecutionAuthorization auth; private final TenantFence tenants; private final DeviceQueryService devices; private final ObjectMapper json; private final ScopeFilter scopeFilter; private final DeviceAccessScope deviceScopes; private final IdempotencyStore idem; private final AuditService audit;
    public OperationalPortraitService(JdbcTemplate jdbc,DatabaseTime time,ExecutionAuthorization auth,TenantFence tenants,DeviceQueryService devices,ObjectMapper json,ScopeFilter scopeFilter,DeviceAccessScope deviceScopes,IdempotencyStore idem,AuditService audit) { this.jdbc=jdbc;this.time=time;this.auth=auth;this.tenants=tenants;this.devices=devices;this.json=json;this.scopeFilter=scopeFilter;this.deviceScopes=deviceScopes;this.idem=idem;this.audit=audit; }

    private Rule latestRule(String tenant) {
        var rows=jdbc.query("SELECT id,tenant_id,version,window_days,false_alarm_count,fault_count,offline_count,weak_signal_percent,weak_signal_metric,weak_signal_threshold,low_battery_percent,long_offline_days,selected_labels,enabled,reason,created_at FROM device_portrait_rule_versions WHERE tenant_id=? ORDER BY version DESC LIMIT 1",(rs,n)->new Rule(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getBigDecimal(8),rs.getString(9),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getInt(12),decodeLabels(rs.getString(13)),rs.getBoolean(14),rs.getString(15),rs.getObject(16,OffsetDateTime.class)),tenant);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private List<String> decodeLabels(String raw) { try { return json.readValue(raw,new TypeReference<List<String>>(){}); } catch(Exception e) { throw ApiError.conflict("PORTRAIT_RULE_INVALID","画像规则标签记录不可读取"); } }
    @Transactional(readOnly=true) public RulePage rules(String actor,String tenant,ScopeFilter.Selection selection) {
        var scope=scopeFilter.units(actor,"r","tenant_id",tenant,selection);
        var rows=jdbc.query("SELECT r.id,r.tenant_id,r.version,r.window_days,r.false_alarm_count,r.fault_count,r.offline_count,r.weak_signal_percent,r.weak_signal_metric,r.weak_signal_threshold,r.low_battery_percent,r.long_offline_days,r.selected_labels,r.enabled,r.reason,r.created_at FROM device_portrait_rule_versions r WHERE NOT EXISTS (SELECT 1 FROM device_portrait_rule_versions n WHERE n.tenant_id=r.tenant_id AND n.version>r.version)"+scope.and()+" ORDER BY r.tenant_id",(rs,n)->new Rule(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getBigDecimal(8),rs.getString(9),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getInt(12),decodeLabels(rs.getString(13)),rs.getBoolean(14),rs.getString(15),rs.getObject(16,OffsetDateTime.class)),scope.bindings().toArray());
        return new RulePage(List.copyOf(rows));
    }
    @Transactional public Rule configure(AuthService.Identity actor,String tenant,String key,Configure b,String request) {
        auth.lockRequest(actor,tenant,List.of("profiles:write")); tenants.lockEnabled(tenant); idem.validateKey(key);
        if(b.expectedRuleVersion()<0) throw ApiError.bad("画像规则预期版本无效");
        var fingerprint=idem.fingerprint(new Configure(b.expectedRuleVersion(),b.windowDays(),b.falseAlarmCount(),b.faultCount(),b.offlineCount(),b.weakSignalPercent(),b.weakSignalMetric(),b.weakSignalThreshold(),b.lowBatteryPercent(),b.longOfflineDays(),b.selectedLabels(),b.enabled(),b.reason()));
        String operation="device-portrait-rule";
        Rule replay=idem.replay(actor.user().id(),operation,key,fingerprint,Rule.class); if(replay!=null)return replay;
        if(b.windowDays()<1||b.windowDays()>365||b.falseAlarmCount()<1||b.falseAlarmCount()>1_000_000||b.faultCount()<1||b.faultCount()>1_000_000||b.offlineCount()<1||b.offlineCount()>1_000_000||b.longOfflineDays()<1||b.longOfflineDays()>365||!between(b.weakSignalPercent(),BigDecimal.ONE,new BigDecimal("100"))||!between(b.lowBatteryPercent(),BigDecimal.ZERO,new BigDecimal("100"))||!decimalFits(b.weakSignalPercent())||!decimalFits(b.lowBatteryPercent())) throw ApiError.bad("画像规则阈值超出有效范围");
        if(b.reason()==null||b.reason().isBlank()||b.reason().length()>1000) throw ApiError.bad("必须说明配置变更原因");
        if(b.selectedLabels()==null||b.selectedLabels().size()>LABELS.size()||new HashSet<>(b.selectedLabels()).size()!=b.selectedLabels().size()||!LABELS.containsAll(b.selectedLabels())) throw ApiError.bad("画像标签选择无效");
        boolean weakSelected=b.selectedLabels().contains("WEAK_SIGNAL");
        if(weakSelected&&(b.weakSignalMetric()==null||!Set.of("rssi","rsrp").contains(b.weakSignalMetric())||!decimalFits(b.weakSignalThreshold()))) throw ApiError.bad("选择弱信号标签时必须明确选择 RSSI 或 RSRP 指标并填写有效数值阈值");
        if(!weakSelected&&(b.weakSignalMetric()!=null||b.weakSignalThreshold()!=null)) throw ApiError.bad("未选择弱信号标签时不应提交信号判定参数");
        Rule current=latestRule(tenant); int actualVersion=current==null?0:current.version();
        if(actualVersion!=b.expectedRuleVersion()) throw ApiError.conflict("REVISION_CONFLICT","画像规则已更新，请刷新后重开操作");
        int version=actualVersion+1; String id=UUID.randomUUID().toString(); OffsetDateTime now=time.now();
        String selected; try { selected=json.writeValueAsString(b.selectedLabels()); } catch(Exception e) { throw ApiError.bad("画像标签选择无效"); }
        jdbc.update("INSERT INTO device_portrait_rule_versions(id,tenant_id,version,window_days,false_alarm_count,fault_count,offline_count,weak_signal_percent,weak_signal_metric,weak_signal_threshold,low_battery_percent,long_offline_days,selected_labels,enabled,reason,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,tenant,version,b.windowDays(),b.falseAlarmCount(),b.faultCount(),b.offlineCount(),b.weakSignalPercent(),b.weakSignalMetric(),b.weakSignalThreshold(),b.lowBatteryPercent(),b.longOfflineDays(),selected,b.enabled(),b.reason().trim(),actor.user().id(),now);
        Rule result=latestRule(tenant);audit.recordReason(actor.user().id(),tenant,"DEVICE_PORTRAIT_RULE_CONFIGURE",tenant,"SUCCESS",request,b.reason().trim());idem.save(actor.user().id(),operation,key,fingerprint,result);return result;
    }
    private boolean between(BigDecimal v,BigDecimal min,BigDecimal max){return v!=null&&v.compareTo(min)>=0&&v.compareTo(max)<=0;}
    private boolean decimalFits(BigDecimal value){return value!=null&&value.scale()<=36&&value.precision()<=64;}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(String actor,String tenant,ScopeFilter.Selection selection,String label,String keyword,int page,int size) {
        if(label!=null&&!LABELS.contains(label)) throw ApiError.bad("画像标签无效");
        if(page<1||size<1||size>200) throw ApiError.bad("分页参数无效");
        Rule rule=latestRule(tenant); if(rule==null) return new Page(List.of(),0,page,size,Map.of());
        var tree=scopeFilter.devices(actor,"d",tenant,selection); var access=deviceScopes.predicate(actor,"d.tenant_id","d.id"); var args=new ArrayList<Object>(); args.add(tenant); args.addAll(access.bindings()); args.addAll(tree.bindings());
        String where=" WHERE d.tenant_id=? AND d.is_simulated=FALSE AND d.archived_at IS NULL AND ("+access.sql()+")"+tree.and(); if(keyword!=null&&!keyword.isBlank()){where+=" AND (LOWER(d.name) LIKE ? OR LOWER(d.id) LIKE ?)";String q="%"+keyword.trim().toLowerCase(Locale.ROOT)+"%";args.add(q);args.add(q);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM devices d"+where,Long.class,args.toArray());
        String columns="SELECT d.id,d.tenant_id,d.name,d.address,d.place_id,d.last_message_at,d.product_version_id,d.is_simulated FROM devices d";
        var mapper=(org.springframework.jdbc.core.RowMapper<DeviceSummary>)(rs,n)->new DeviceSummary(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class),rs.getString(7),rs.getBoolean(8));
        List<DeviceSummary> rows;
        OffsetDateTime asOf=time.now();
        if(label==null) {
            var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
            rows=jdbc.query(columns+where+" ORDER BY d.name,d.id LIMIT ? OFFSET ?",mapper,pageArgs.toArray());
        } else {
            var candidates=jdbc.query(columns+where+" ORDER BY d.name,d.id",mapper,args.toArray());
            var matches=new ArrayList<DeviceSummary>();
            for(var candidate:candidates) if(evaluate(candidate,rule,asOf).stream().anyMatch(result->result.label().equals(label)&&result.matched())) matches.add(candidate);
            total=matches.size();int start=Math.min((page-1)*size,matches.size()),end=Math.min(start+size,matches.size());rows=List.copyOf(matches.subList(start,end));
        }
        var counts=new LinkedHashMap<String,Long>(); for(String name:LABELS) counts.put(name,0L);
        for(var row:rows) { var results=evaluate(row,rule,asOf); for(var result:results) if(result.matched()) counts.compute(result.label(),(k,v)->v+1); }
        return new Page(rows,total,page,size,Map.copyOf(counts));
    }
    @Transactional public Snapshot evaluateAndSave(AuthService.Identity actor,String tenant,String deviceId,String key,Evaluate b,ScopeFilter.Selection selection,String request) {
        auth.lockRequest(actor,tenant,List.of("profiles:write")); tenants.lockEnabled(tenant); idem.validateKey(key);
        if(b.expectedRuleVersion()<1) throw ApiError.bad("画像分析预期规则版本无效");
        if(b.reason()==null||b.reason().isBlank()||b.reason().length()>1000) throw ApiError.bad("必须填写重新分析原因");
        devices.device(actor.user().id(),tenant,deviceId,false); var visible=scopeFilter.devices(actor.user().id(),"d",tenant,selection);
        var args=new ArrayList<Object>();args.add(tenant);args.add(deviceId);args.addAll(visible.bindings());
        if(jdbc.query("SELECT d.id FROM devices d WHERE d.tenant_id=? AND d.id=? AND d.is_simulated=FALSE AND d.archived_at IS NULL"+visible.and(),(rs,n)->rs.getString(1),args.toArray()).isEmpty()) throw ApiError.missing();
        String operation="device-portrait-evaluate";String fingerprint=idem.fingerprint(List.of(tenant,deviceId,b.expectedRuleVersion(),b.reason().trim()));
        Snapshot replay=idem.replay(actor.user().id(),operation,key,fingerprint,Snapshot.class);if(replay!=null)return replay;
        Rule rule=latestRule(tenant);if(rule==null||!rule.enabled())throw ApiError.conflict("PORTRAIT_RULE_NOT_CONFIGURED","单位尚未配置启用的运维画像规则");
        if(rule.version()!=b.expectedRuleVersion())throw ApiError.conflict("REVISION_CONFLICT","画像规则已更新，请刷新后重新分析");
        var row=jdbc.queryForObject("SELECT id,tenant_id,name,address,place_id,last_message_at,product_version_id,is_simulated FROM devices WHERE tenant_id=? AND id=? AND archived_at IS NULL",(rs,n)->new DeviceSummary(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,OffsetDateTime.class),rs.getString(7),rs.getBoolean(8)),tenant,deviceId);
        var now=time.now();var start=now.minusDays(rule.windowDays());var results=evaluate(row,rule,now); String resultJson;
        try { resultJson=json.writeValueAsString(results); } catch(Exception e) { throw ApiError.bad("画像分析结果不可序列化"); }
        String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO device_portrait_snapshots(id,tenant_id,device_id,rule_id,rule_version,window_start,window_end,evaluated_at,result_json,actor_id,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,tenant,deviceId,rule.id(),rule.version(),start,now,now,resultJson,actor.user().id(),b.reason().trim());
        Snapshot result=new Snapshot(id,tenant,deviceId,rule.id(),rule.version(),start,now,now,results,b.reason().trim());
        audit.recordReason(actor.user().id(),tenant,"DEVICE_PORTRAIT_EVALUATE",deviceId,"SUCCESS",request,b.reason().trim());idem.save(actor.user().id(),operation,key,fingerprint,result);return result;
    }
    private List<LabelResult> evaluate(DeviceSummary d,Rule r,OffsetDateTime now) {
        OffsetDateTime from=now.minusDays(r.windowDays());
        var out=new ArrayList<LabelResult>();
        if(!r.enabled()) return LABELS.stream().map(label->result(label,"NOT_SELECTED",0,null,"规则停用",null)).toList();
        if(!r.selectedLabels().contains("FREQUENT_FALSE_ALARM")) out.add(result("FREQUENT_FALSE_ALARM","NOT_SELECTED",0,null,"未选择标签",null));
        else {
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE tenant_id=? AND device_id=? AND conclusion='FALSE_ALARM' AND occurred_at>=? AND occurred_at<=?",Integer.class,d.tenantId(),d.id(),from,now);
            int support=eventSamples(d,from,now)+alarmSamples(d,from,now);
            var threshold=PortraitThresholds.count(count,support>0,r.falseAlarmCount());
            out.add(new LabelResult("FREQUENT_FALSE_ALARM",threshold.status(),threshold.matched(),count,Integer.toString(count),support==0?"窗口内没有可验证的设备事件或报警记录，无法确认零误报":"已持久化的误报结论数≥"+r.falseAlarmCount(),latestAlarm(d,from,now)));
        }
        out.add(eventCount(d,r,from,now,"FREQUENT_FAULT","FAULT",r.faultCount()));
        out.add(eventCount(d,r,from,now,"FREQUENT_OFFLINE","OFFLINE",r.offlineCount()));
        if(!r.selectedLabels().contains("WEAK_SIGNAL")) out.add(result("WEAK_SIGNAL","NOT_SELECTED",0,null,"未选择标签",null));
        else out.add(weakSignal(d,r,from,now));
        if(!r.selectedLabels().contains("LOW_BATTERY")) out.add(result("LOW_BATTERY","NOT_SELECTED",0,null,"未选择标签",null));
        else {
            var values=jdbc.query("SELECT battery_percent,observed_at FROM real_device_battery_observations WHERE tenant_id=? AND device_id=? AND observed_at>=? AND observed_at<=? ORDER BY observed_at DESC",(rs,n)->new Object[]{rs.getBigDecimal(1),rs.getObject(2,OffsetDateTime.class)},d.tenantId(),d.id(),from,now);
            if(values.isEmpty()) {
                var current=jdbc.query("SELECT battery_percent,last_message_at FROM devices WHERE tenant_id=? AND id=? AND battery_percent IS NOT NULL AND last_message_at>=? AND last_message_at<=?",(rs,n)->new Object[]{rs.getBigDecimal(1),rs.getObject(2,OffsetDateTime.class)},d.tenantId(),d.id(),from,now);
                if(current.isEmpty()) out.add(result("LOW_BATTERY","MISSING",0,null,"窗口内没有电量观测",null));
                else {BigDecimal battery=(BigDecimal)current.getFirst()[0];OffsetDateTime at=(OffsetDateTime)current.getFirst()[1];out.add(new LabelResult("LOW_BATTERY","OK",PortraitThresholds.lowBattery(battery,r.lowBatteryPercent()),1,battery.toPlainString(),"设备当前持久化电量；阈值≤"+r.lowBatteryPercent().toPlainString()+"%",at));}
            } else {BigDecimal battery=(BigDecimal)values.getFirst()[0];OffsetDateTime at=(OffsetDateTime)values.getFirst()[1];out.add(new LabelResult("LOW_BATTERY","OK",PortraitThresholds.lowBattery(battery,r.lowBatteryPercent()),values.size(),battery.toPlainString(),"最近一条持久化电量观测；阈值≤"+r.lowBatteryPercent().toPlainString()+"%",at));}
        }
        if(!r.selectedLabels().contains("LONG_OFFLINE")) out.add(result("LONG_OFFLINE","NOT_SELECTED",0,null,"未选择标签",null));
        else if(d.lastMessageAt()==null) out.add(result("LONG_OFFLINE","MISSING",0,null,"设备没有最近上报时间",null));
        else out.add(new LabelResult("LONG_OFFLINE","OK",PortraitThresholds.longOffline(d.lastMessageAt(),now,r.longOfflineDays()),1,Long.toString(Math.max(0,java.time.Duration.between(d.lastMessageAt(),now).toDays())),"按最近设备上报时间与配置天数计算",d.lastMessageAt()));
        return List.copyOf(out);
    }
    private LabelResult weakSignal(DeviceSummary device,Rule rule,OffsetDateTime from,OffsetDateTime now) {
        if(rule.weakSignalMetric()==null||rule.weakSignalThreshold()==null) return result("WEAK_SIGNAL","UNSUPPORTED",0,null,"未配置明确的信号指标和阈值",null);
        var attributes=jdbc.query("SELECT data_type,unit FROM product_version_attributes WHERE product_version_id=? AND attribute_key=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},device.productVersionId(),rule.weakSignalMetric());
        if(attributes.isEmpty()) return result("WEAK_SIGNAL","UNSUPPORTED",0,null,"设备产品版本未声明指标 "+rule.weakSignalMetric(),null);
        String type=attributes.getFirst()[0]==null?"":attributes.getFirst()[0].trim().toLowerCase(Locale.ROOT);
        if(!Set.of("number","numeric","integer","int","decimal","float","double","long").contains(type)) return result("WEAK_SIGNAL","UNSUPPORTED",0,null,"产品版本指标类型不是已确认的数值类型",null);
        var observations=jdbc.query("SELECT params_json,observed_at FROM legacy_ingest_events WHERE tenant_id=? AND device_id=? AND observed_at>=? AND observed_at<=? ORDER BY observed_at",(rs,n)->new Object[]{rs.getString(1),rs.getObject(2,OffsetDateTime.class)},device.tenantId(),device.id(),from,now);
        int samples=0,weak=0;OffsetDateTime latest=null;
        for(Object[] observation:observations) {
            try {
                JsonNode root=json.readTree((String)observation[0]);JsonNode value=root.get(rule.weakSignalMetric());
                if(value==null||!value.isNumber()) continue;
                samples++;if(PortraitThresholds.weakSignal(value.decimalValue(),rule.weakSignalThreshold())) weak++;
                latest=(OffsetDateTime)observation[1];
            } catch(Exception ignored) { /* malformed/untyped payload is not a numeric observation */ }
        }
        if(samples==0) return result("WEAK_SIGNAL","MISSING",0,null,"产品版本支持数值指标，但窗口内没有该指标的有效样本",null);
        BigDecimal ratio=PortraitThresholds.weakPercent(weak,samples);
        return new LabelResult("WEAK_SIGNAL","OK",PortraitThresholds.weakPercentAtLeast(weak,samples,rule.weakSignalPercent()),samples,ratio.toPlainString()+"%","数值 "+rule.weakSignalMetric()+"≤"+rule.weakSignalThreshold().toPlainString()+" 的样本占有效 "+rule.weakSignalMetric()+" 样本数；配置比例阈值≥"+rule.weakSignalPercent().toPlainString()+"% · 单位 "+Objects.toString(attributes.getFirst()[1],"未声明"),latest);
    }
    private LabelResult eventCount(DeviceSummary d,Rule r,OffsetDateTime from,OffsetDateTime now,String label,String kind,int threshold) {
        if(!r.enabled()||!r.selectedLabels().contains(label)) return result(label,"NOT_SELECTED",0,null,"未选择或规则停用",null);
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE tenant_id=? AND device_id=? AND kind=? AND observed_at>=? AND observed_at<=?",Integer.class,d.tenantId(),d.id(),kind,from,now);
        OffsetDateTime latest=jdbc.query("SELECT MAX(observed_at) FROM legacy_ingest_events WHERE tenant_id=? AND device_id=? AND kind=? AND observed_at>=? AND observed_at<=?",(rs,i)->rs.getObject(1,OffsetDateTime.class),d.tenantId(),d.id(),kind,from,now).getFirst();
        var countResult=PortraitThresholds.count(count,count>0||eventSamples(d,from,now)>0,threshold);
        return new LabelResult(label,countResult.status(),countResult.matched(),count,Integer.toString(count),countResult.status().equals("OK")?"已报告的"+kind+"事件数≥"+threshold:"窗口内无带报告时间的设备事件，不能把零事件视为有效信号",latest);
    }
    private int eventSamples(DeviceSummary d,OffsetDateTime from,OffsetDateTime now) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_events WHERE tenant_id=? AND device_id=? AND observed_at>=? AND observed_at<=?",Integer.class,d.tenantId(),d.id(),from,now);
    }
    private int alarmSamples(DeviceSummary d,OffsetDateTime from,OffsetDateTime now) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM alarms WHERE tenant_id=? AND device_id=? AND occurred_at>=? AND occurred_at<=?",Integer.class,d.tenantId(),d.id(),from,now);
    }
    private OffsetDateTime latestAlarm(DeviceSummary d,OffsetDateTime from,OffsetDateTime now){return jdbc.query("SELECT MAX(occurred_at) FROM alarms WHERE tenant_id=? AND device_id=? AND conclusion='FALSE_ALARM' AND occurred_at>=? AND occurred_at<=?",(rs,i)->rs.getObject(1,OffsetDateTime.class),d.tenantId(),d.id(),from,now).getFirst();}
    private LabelResult result(String label,String status,int count,String value,String evidence,OffsetDateTime at){return new LabelResult(label,status,false,count,value,evidence,at);}
    @Transactional(readOnly=true) public List<Snapshot> history(String actor,String tenant,String deviceId,int limit,ScopeFilter.Selection selection) {
        devices.device(actor,tenant,deviceId,false);var sc=scopeFilter.devices(actor,"d",tenant,selection);var a=new ArrayList<Object>();a.add(tenant);a.add(deviceId);a.addAll(sc.bindings());if(jdbc.query("SELECT d.id FROM devices d WHERE d.tenant_id=? AND d.id=?"+sc.and(),(rs,n)->rs.getString(1),a.toArray()).isEmpty())throw ApiError.missing();
        return jdbc.query("SELECT s.id,s.tenant_id,s.device_id,s.rule_id,s.rule_version,s.window_start,s.window_end,s.evaluated_at,s.result_json,s.reason FROM device_portrait_snapshots s WHERE s.tenant_id=? AND s.device_id=? ORDER BY s.evaluated_at DESC,s.id LIMIT ?",(rs,n)->{try{return new Snapshot(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class),rs.getObject(8,OffsetDateTime.class),json.readValue(rs.getString(9),new TypeReference<List<LabelResult>>(){}),rs.getString(10));}catch(Exception e){throw ApiError.conflict("PORTRAIT_SNAPSHOT_INVALID","画像历史记录不可读取");}},tenant,deviceId,Math.min(Math.max(limit,1),100));
    }
}
