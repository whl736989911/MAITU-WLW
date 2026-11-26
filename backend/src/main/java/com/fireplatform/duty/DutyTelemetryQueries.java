package com.fireplatform.duty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.DatabaseTime;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fireplatform.product.ProductMeasurementProjection;
import com.fireplatform.shared.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Typed projection of explicitly supported declared telemetry; never returns source params JSON. */
@Service
public class DutyTelemetryQueries {
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("temperature","温度"),Map.entry("smoke_value","烟雾浓度"),Map.entry("battery","电量"),
        Map.entry("rssi","RSSI"),Map.entry("snr","信噪比"),Map.entry("rsrp","RSRP"),
        Map.entry("humidity","湿度"),Map.entry("signal_level","信号等级"),
        Map.entry("temperature_value","温度"),Map.entry("smoke_concentration","烟雾浓度"),
        Map.entry("sinr","SINR"));
    private static final Set<String> METRICS=LABELS.keySet();
    private static final Set<String> NUMERIC_STRING_METRICS=Set.of("rsrp","sinr");
    private static final String LEGACY_NUMBER="LEGACY_NUMBER";
    private static final Set<String> NUMERIC_TYPES=Set.of("NUMBER","NUMERIC","DECIMAL","INTEGER","INT","FLOAT","DOUBLE","LONG",LEGACY_NUMBER);
    public static final String GENERIC_BATTERY_PREFIX="GENERIC_BATTERY:";
    private static final String TELEMETRY_SOURCE="(SELECT e.id,e.tenant_id,e.device_id,e.kind,e.method,e.params_json,e.observed_at,e.created_at,COALESCE(m.received_at,rm.received_at) AS received_at,COALESCE(m.vendor_platform,e.source_platform) AS vendor_platform FROM legacy_ingest_events e LEFT JOIN legacy_ingest_messages m ON m.id=e.message_id AND m.tenant_id=e.tenant_id LEFT JOIN raw_messages rm ON rm.id=e.raw_message_id AND rm.tenant_id=e.tenant_id UNION ALL SELECT '"+GENERIC_BATTERY_PREFIX+"'||o.id,o.tenant_id,o.device_id,'PARAM','GENERIC_BATTERY',('{\"battery\":'||o.battery_percent||'}'),o.observed_at,o.recorded_at,NULL,'GENERIC_INGEST' FROM real_device_battery_observations o WHERE o.source_kind='GENERIC_INGEST')";
    private static final String REAL_DEVICE=" AND EXISTS(SELECT 1 FROM devices real_device WHERE real_device.id=e.device_id AND real_device.tenant_id=e.tenant_id AND real_device.is_simulated=FALSE)";
    public record Metric(String key,String label,String valueType) { }
    public record Reading(String metric,String value,String unit,String unitStatus,String label,String valueType,
        OffsetDateTime observedAt,OffsetDateTime receivedAt,OffsetDateTime recordedAt) { }
    public record Sample(String eventId,String tenantId,String deviceId,String deviceName,String address,
        OffsetDateTime observedAt,OffsetDateTime receivedAt,OffsetDateTime recordedAt,List<Reading> readings) { }
    public record TrendPoint(String date,long sampleCount,String average,String minimum,String maximum) { }
    public record TrendSeries(String unit,String unitStatus,String sourceVersion,String valueType,List<TrendPoint> points) { }
    public record Analytics(OffsetDateTime from,OffsetDateTime to,String timezone,int days,String metric,long untimedSampleCount,List<TrendSeries> series) { }
    private record Raw(String id,String tenant,String device,String name,String address,String version,String params,OffsetDateTime observed,OffsetDateTime created,OffsetDateTime received,String platform) { }
    private final JdbcTemplate jdbc; private final DeviceAccessScope access; private final ScopeFilter scope; private final PageSizePolicy sizes; private final ObjectReader sourceJson; private final DatabaseTime time; private final ProductMeasurementProjection measurements;
    public DutyTelemetryQueries(JdbcTemplate jdbc,DeviceAccessScope access,ScopeFilter scope,PageSizePolicy sizes,ObjectMapper mapper,DatabaseTime time,ProductMeasurementProjection measurements){this.jdbc=jdbc;this.access=access;this.scope=scope;this.sizes=sizes;this.sourceJson=mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);this.time=time;this.measurements=measurements;}
    @Transactional(readOnly=true)
    public List<Metric> metrics(String actor,String tenant,ScopeFilter.Selection selection){
        if(selection.empty())return List.of();
        var labels=new TreeMap<String,String>(); var types=new TreeMap<String,String>();
        LABELS.forEach((key,label)->{labels.put(key,label);types.put(key,"NUMBER");});
        jdbc.query("SELECT metric_key,label FROM hikvision_metric_catalog",(RowCallbackHandler)rs->{labels.put(rs.getString(1),rs.getString(2));types.put(rs.getString(1),"NUMBER");});
        var a=access.predicate(actor,"d.tenant_id","d.id");var s=scope.clause("d",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();if(tenant!=null)args.add(tenant);args.addAll(a.bindings());args.addAll(s.bindings());
        jdbc.query("SELECT DISTINCT p.attribute_key,p.name,p.data_type FROM product_version_attributes p JOIN devices d ON d.product_version_id=p.product_version_id WHERE d.is_simulated=FALSE"+(tenant==null?"":" AND d.tenant_id=?")+a.and()+s.and(),(RowCallbackHandler)rs->{String key=rs.getString(1);if(validKey(key)&&numericType(rs.getString(3))){labels.putIfAbsent(key,rs.getString(2));types.putIfAbsent(key,"NUMBER");}},args.toArray());
        var versionArgs=new ArrayList<Object>();if(tenant!=null)versionArgs.add(tenant);versionArgs.addAll(a.bindings());versionArgs.addAll(s.bindings());
        var versions=jdbc.queryForList("SELECT DISTINCT d.product_version_id FROM devices d WHERE d.is_simulated=FALSE AND d.product_version_id IS NOT NULL"+(tenant==null?"":" AND d.tenant_id=?")+a.and()+s.and(),String.class,versionArgs.toArray());
        measurements.forVersions(versions).forEach((version,declared)->declared.forEach((key,item)->{labels.put(key,item.label());types.put(key,item.valueType());}));
        return labels.entrySet().stream().map(e->new Metric(e.getKey(),e.getValue(),types.getOrDefault(e.getKey(),"NUMBER"))).toList();
    }
    private static boolean validKey(String key){return key!=null&&key.matches("[a-z][a-z0-9_]{0,95}");}
    private void requireMetric(String metric){
        if(!validKey(metric))throw ApiError.bad("监测指标无效");
        if(METRICS.contains(metric))return;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_metric_catalog WHERE metric_key=?",Long.class,metric)>0)return;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM product_version_attributes WHERE attribute_key=? AND UPPER(data_type) IN ('NUMBER','NUMERIC','DECIMAL','INTEGER','INT','FLOAT','DOUBLE','LONG','LEGACY_NUMBER')",Long.class,metric)>0)return;
        if(measurements.declares(metric))return;
        throw ApiError.bad("监测指标无效");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Sample> history(String actor,String tenant,ScopeFilter.Selection selection,String metric,OffsetDateTime from,OffsetDateTime to,int page,Integer requestedSize){
        return history(actor,tenant,selection,metric,from,to,page,requestedSize,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Sample> historyForDevice(String actor,String tenant,String deviceId,OffsetDateTime from,OffsetDateTime to,int page,Integer requestedSize){
        requireDevice(actor,tenant,deviceId);
        return history(actor,tenant,ScopeFilter.Selection.all(),null,from,to,page,requestedSize,deviceId);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Sample> history(String actor,String tenant,ScopeFilter.Selection selection,String metric,OffsetDateTime from,OffsetDateTime to,int page,Integer requestedSize,String deviceId){
        if(metric!=null)requireMetric(metric);
        var p=Pagination.of(page,requestedSize,sizes.defaultPageSize()); if(selection.empty())return new Page<>(List.of(),0,p.page(),p.size());
        var a=access.predicate(actor,"e.tenant_id","e.device_id");var s=scope.alarmDevices("e",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();if(tenant!=null)args.add(tenant);if(deviceId!=null)args.add(deviceId);args.addAll(a.bindings());args.addAll(s.bindings());
        var where=new StringBuilder(" WHERE (e.kind IN ('PARAM','HEARTBEAT') OR (e.vendor_platform='formttl' AND e.kind IN ('ALARM','FAULT')))"+REAL_DEVICE);if(tenant!=null)where.append(" AND e.tenant_id=?");if(deviceId!=null)where.append(" AND e.device_id=?");where.append(a.and()).append(s.and());if(from!=null){where.append(" AND e.observed_at>=?");args.add(from);}if(to!=null){where.append(" AND e.observed_at<?");args.add(to);}
        String fromSql=" FROM "+TELEMETRY_SOURCE+" e LEFT JOIN devices d ON d.id=e.device_id AND d.tenant_id=e.tenant_id";
        if(metric!=null){where.append(" AND e.params_json LIKE ?");args.add("%\""+metric+"\"%");}
        long total=jdbc.queryForObject("SELECT COUNT(*)"+fromSql+where,Long.class,args.toArray());args.add(p.size());args.add(p.offset());
        var raw=jdbc.query("SELECT e.id,e.tenant_id,e.device_id,d.name,d.address,d.product_version_id,e.params_json,e.observed_at,e.created_at,e.received_at,e.vendor_platform"+fromSql+where+" ORDER BY e.observed_at DESC NULLS LAST,e.created_at DESC,e.id DESC LIMIT ? OFFSET ?",(rs,n)->raw(rs),args.toArray());
        var declared=declaredForRows(raw);var samples=raw.stream().map(r->sample(r,declared.getOrDefault(r.version()==null?"":r.version(),Map.of()),metric)).toList();return new Page<>(samples,total,p.page(),p.size());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Analytics analytics(String actor,String tenant,ScopeFilter.Selection selection,String metric,int days){
        requireMetric(metric);if(days!=7&&days!=30)throw ApiError.bad("监测周期仅支持7天或30天");
        var to=time.now().withOffsetSameInstant(java.time.ZoneOffset.UTC);var from=to.minusDays(days);
        if(selection.empty())return new Analytics(from,to,"UTC",days,metric,0,List.of());
        var a=access.predicate(actor,"e.tenant_id","e.device_id");var s=scope.alarmDevices("e",selection.tenantIds(),selection.placeIds());
        var args=new ArrayList<Object>();if(tenant!=null)args.add(tenant);args.addAll(a.bindings());args.addAll(s.bindings());
        String visible=" WHERE (e.kind IN ('PARAM','HEARTBEAT') OR (e.vendor_platform='formttl' AND e.kind IN ('ALARM','FAULT')))"+REAL_DEVICE+(tenant==null?"":" AND e.tenant_id=?")+a.and()+s.and();
        long[] untimed={0};
        jdbc.query("SELECT e.params_json FROM "+TELEMETRY_SOURCE+" e"+visible+" AND e.observed_at IS NULL",(RowCallbackHandler)rs->{JsonNode root=parseParams(rs.getString(1));JsonNode value=root==null?null:root.get(metric);if(value!=null&&!value.isNull()&&value.isValueNode()&&!isBatterySentinel(metric,value))untimed[0]++;},args.toArray());
        var timedArgs=new ArrayList<Object>();timedArgs.add(from);timedArgs.add(to);if(tenant!=null)timedArgs.add(tenant);timedArgs.addAll(a.bindings());timedArgs.addAll(s.bindings());
        String timedWhere=" WHERE (e.kind IN ('PARAM','HEARTBEAT') OR (e.vendor_platform='formttl' AND e.kind IN ('ALARM','FAULT')))"+REAL_DEVICE+" AND e.observed_at>=? AND e.observed_at<?"+(tenant==null?"":" AND e.tenant_id=?")+a.and()+s.and();
        String joined=" FROM "+TELEMETRY_SOURCE+" e LEFT JOIN devices d ON d.id=e.device_id AND d.tenant_id=e.tenant_id";
        var versions=jdbc.queryForList("SELECT DISTINCT d.product_version_id"+joined+timedWhere+" AND d.product_version_id IS NOT NULL",String.class,timedArgs.toArray());
        var declared=declaredForVersions(versions);
        record SeriesKey(String unit,String status,String version,String type){}
        class Acc {
            BigDecimal sum,min,max;long count;
            void add(BigDecimal number){count++;if(number==null)return;if(sum==null){sum=number;min=number;max=number;}else{sum=sum.add(number);min=min.min(number);max=max.max(number);}}
        }
        var buckets=new HashMap<SeriesKey,Map<java.time.LocalDate,Acc>>();
        jdbc.query("SELECT e.params_json,e.observed_at,d.product_version_id,e.vendor_platform"+joined+timedWhere,(RowCallbackHandler)rs->{
            JsonNode root=parseParams(rs.getString(1)),value=root==null?null:root.get(metric);if(value==null||value.isNull())return;
            String version=rs.getString(3),platform=rs.getString(4);boolean generic="GENERIC_INGEST".equals(platform);
            var definition=version==null?null:declared.getOrDefault(version,Map.of()).get(metric);
            if(isBatterySentinel(metric,value))return;
            String type=displayType(definition);boolean numeric;BigDecimal numericValue=null;
            if("NUMBER".equals(type)){numericValue=metricNumber(metric,value,legacyNumber(definition));if(numericValue==null)return;numeric=true;}
            else if("STRING".equals(type)){if(!value.isTextual())return;numeric=false;}
            else {if(!value.isBoolean())return;numeric=false;}
            String unit=generic&&"battery".equals(metric)?"%":source(platform,root)?sourceUnit(root,metric):definition==null?null:definition.unit();
            var key=new SeriesKey(unit,unit==null?"UNKNOWN":"DECLARED",unit==null?version:null,type);
            var date=rs.getObject(2,OffsetDateTime.class).withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
            buckets.computeIfAbsent(key,k->new HashMap<>()).computeIfAbsent(date,d->new Acc()).add(numeric?numericValue:null);
        },timedArgs.toArray());
        var first=from.toLocalDate();var series=new ArrayList<TrendSeries>();
        for(var entry:buckets.entrySet()){
            var points=new ArrayList<TrendPoint>(days+1);var key=entry.getKey();boolean stats="DECLARED".equals(key.status())&&"NUMBER".equals(key.type());
            for(int i=0;i<=days;i++){var date=first.plusDays(i);var acc=entry.getValue().get(date);points.add(new TrendPoint(date.toString(),acc==null?0:acc.count,acc==null||!stats?null:acc.sum.divide(BigDecimal.valueOf(acc.count),java.math.MathContext.DECIMAL128).stripTrailingZeros().toPlainString(),acc==null||!stats?null:acc.min.stripTrailingZeros().toPlainString(),acc==null||!stats?null:acc.max.stripTrailingZeros().toPlainString()));}
            series.add(new TrendSeries(key.unit(),key.status(),key.version(),key.type(),List.copyOf(points)));
        }
        series.sort(Comparator.comparing(TrendSeries::unitStatus).thenComparing(TrendSeries::unit,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(TrendSeries::sourceVersion,Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(TrendSeries::valueType));
        return new Analytics(from,to,"UTC",days,metric,untimed[0],List.copyOf(series));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Sample> current(String actor,String tenant,ScopeFilter.Selection selection,String metric,int page,Integer requestedSize){
        return current(actor,tenant,selection,metric,page,requestedSize,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Sample> currentForDevice(String actor,String tenant,String deviceId){
        requireDevice(actor,tenant,deviceId);
        return current(actor,tenant,ScopeFilter.Selection.all(),null,1,1,deviceId);
    }
    private void requireDevice(String actor,String tenant,String deviceId){
        var allowed=access.predicate(actor,"d.tenant_id","d.id");
        var args=new ArrayList<Object>();args.add(tenant);args.add(deviceId);args.addAll(allowed.bindings());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM devices d WHERE d.tenant_id=? AND d.id=? AND d.is_simulated=FALSE"+allowed.and(),Long.class,args.toArray())==0)throw ApiError.missing();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<Sample> current(String actor,String tenant,ScopeFilter.Selection selection,String metric,int page,Integer requestedSize,String deviceId){
        if(metric!=null)requireMetric(metric);var p=Pagination.of(page,requestedSize,sizes.defaultPageSize());if(selection.empty())return new Page<>(List.of(),0,p.page(),p.size());
        var a=access.predicate(actor,"e.tenant_id","e.device_id");var s=scope.alarmDevices("e",selection.tenantIds(),selection.placeIds());
        var whereArgs=new ArrayList<Object>();
        if(tenant!=null)whereArgs.add(tenant);if(deviceId!=null)whereArgs.add(deviceId);whereArgs.addAll(a.bindings());whereArgs.addAll(s.bindings());
        String where=" WHERE (e.kind IN ('PARAM','HEARTBEAT') OR (e.vendor_platform='formttl' AND e.kind IN ('ALARM','FAULT')) OR (e.kind='OTHER' AND e.method='HIKVISION_CURRENT_MONITOR' AND e.vendor_platform='hikvision_firecloud'))"+REAL_DEVICE+" AND e.observed_at IS NOT NULL AND e.device_id IS NOT NULL"+(tenant==null?"":" AND e.tenant_id=?")+(deviceId==null?"":" AND e.device_id=?")+a.and()+s.and();
        var filterArgs=new ArrayList<Object>(whereArgs);
        if(metric!=null){where+=" AND e.params_json LIKE ?";filterArgs.add("%\""+metric+"\"%");}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT e.tenant_id,e.device_id FROM "+TELEMETRY_SOURCE+" e"+where+" GROUP BY e.tenant_id,e.device_id) current_devices",Long.class,filterArgs.toArray());
        var pageArgs=new ArrayList<Object>(filterArgs);pageArgs.add(p.size());pageArgs.add(p.offset());
        String cte="WITH ranked AS (SELECT e.id,e.tenant_id,e.device_id,d.name,d.address,d.product_version_id,e.params_json,e.observed_at,e.created_at,e.received_at,e.vendor_platform,ROW_NUMBER() OVER(PARTITION BY e.tenant_id,e.device_id ORDER BY e.observed_at DESC,CASE WHEN e.method='HIKVISION_CURRENT_MONITOR' THEN 0 ELSE 1 END,e.created_at DESC,e.id DESC) AS row_num FROM "+TELEMETRY_SOURCE+" e LEFT JOIN devices d ON d.id=e.device_id AND d.tenant_id=e.tenant_id";
        var raw=jdbc.query(cte+where+") SELECT id,tenant_id,device_id,name,address,product_version_id,params_json,observed_at,created_at,received_at,vendor_platform FROM ranked WHERE row_num=1 ORDER BY tenant_id,device_id LIMIT ? OFFSET ?",(rs,n)->raw(rs),pageArgs.toArray());
        var declared=declaredForRows(raw);
        if(metric!=null){var result=raw.stream().map(r->sample(r,declared.getOrDefault(r.version()==null?"":r.version(),Map.of()),metric)).toList();return new Page<>(result,total,p.page(),p.size());}
        // Default current is the newest value per metric, not merely the newest whole packet: a flat push that
        // carries one metric must neither erase the other stored latest metrics nor vanish from this view.
        var readings=new java.util.LinkedHashMap<String,java.util.TreeMap<String,Reading>>();
        for(Raw r:raw)readings.put(deviceKey(r),new java.util.TreeMap<>());
        for(Raw r:raw){var m=declared.getOrDefault(r.version()==null?"":r.version(),Map.of());for(Reading reading:sample(r,m,null).readings())readings.get(deviceKey(r)).put(reading.metric(),reading);}
        var missing=metricUniverse(raw);
        missing.removeIf(key->readings.values().stream().allMatch(values->values.containsKey(key)));
        if(!missing.isEmpty()&&!raw.isEmpty()){
            var deviceIds=raw.stream().map(Raw::device).distinct().toList();var placeholders=String.join(",",Collections.nCopies(deviceIds.size(),"?"));
            for(String key:missing){
                var queryArgs=new ArrayList<Object>(whereArgs);queryArgs.add("%\""+key+"\"%");queryArgs.addAll(deviceIds);
                String sql=cte+where+" AND e.params_json LIKE ? AND e.device_id IN ("+placeholders+")) SELECT id,tenant_id,device_id,name,address,product_version_id,params_json,observed_at,created_at,received_at,vendor_platform FROM ranked WHERE row_num=1";
                jdbc.query(sql,(RowCallbackHandler)rs->{
                    Raw row=raw(rs);var map=readings.get(deviceKey(row));if(map==null||map.containsKey(key))return;
                    var found=sample(row,declared.getOrDefault(row.version()==null?"":row.version(),Map.of()),key);
                    if(!found.readings().isEmpty())map.put(key,found.readings().getFirst());
                },queryArgs.toArray());
            }
        }
        var result=new ArrayList<Sample>(raw.size());
        for(Raw r:raw){var merged=readings.get(deviceKey(r));result.add(new Sample(r.id(),r.tenant(),r.device(),r.name(),r.address(),r.observed(),r.received(),r.created(),List.copyOf(merged.values())));}
        return new Page<>(result,total,p.page(),p.size());
    }
    private Set<String> metricUniverse(List<Raw> rows){
        var keys=new java.util.TreeSet<String>(LABELS.keySet());
        jdbc.query("SELECT metric_key FROM hikvision_metric_catalog",(RowCallbackHandler)rs->{String key=rs.getString(1);if(validKey(key))keys.add(key);});
        var versions=rows.stream().map(Raw::version).filter(Objects::nonNull).distinct().toList();
        if(!versions.isEmpty()){
            var queryArgs=new ArrayList<Object>(versions);var placeholders=String.join(",",Collections.nCopies(versions.size(),"?"));
            jdbc.query("SELECT attribute_key,data_type FROM product_version_attributes WHERE product_version_id IN ("+placeholders+")",(RowCallbackHandler)rs->{String key=rs.getString("attribute_key");if(validKey(key)&&numericType(rs.getString("data_type")))keys.add(key);},queryArgs.toArray());
            measurements.forVersions(versions).values().forEach(declared->keys.addAll(declared.keySet()));
        }
        return keys;
    }
    private static String deviceKey(Raw row){return row.tenant()+"\u0000"+row.device();}
    private Map<String,Map<String,ProductMeasurementProjection.Declared>> declaredForRows(List<Raw> rows){
        var versions=rows.stream().map(Raw::version).filter(Objects::nonNull).distinct().toList();
        return declaredForVersions(versions);
    }
    private Map<String,Map<String,ProductMeasurementProjection.Declared>> declaredForVersions(List<String> versions){
        if(versions.isEmpty())return Map.of();
        var result=new HashMap<String,Map<String,ProductMeasurementProjection.Declared>>();
        var args=new ArrayList<Object>(versions);var placeholders=String.join(",",Collections.nCopies(versions.size(),"?"));
        jdbc.query("SELECT product_version_id,attribute_key,name,unit,data_type FROM product_version_attributes WHERE product_version_id IN ("+placeholders+")",(RowCallbackHandler)rs->{
            String key=rs.getString("attribute_key"),type=rs.getString("data_type");
            if(validKey(key)&&numericType(type)) result.computeIfAbsent(rs.getString("product_version_id"),x->new HashMap<>())
                .putIfAbsent(key,new ProductMeasurementProjection.Declared(key,rs.getString("name"),nonblank(rs.getString("unit")),
                    LEGACY_NUMBER.equalsIgnoreCase(type)?LEGACY_NUMBER:"NUMBER"));
        },args.toArray());
        measurements.forVersions(versions).forEach((version,declared)->{var target=result.computeIfAbsent(version,x->new HashMap<>());declared.forEach(target::putIfAbsent);});
        return result;
    }
    private static boolean numericType(String type){return type!=null&&NUMERIC_TYPES.contains(type.toUpperCase(Locale.ROOT));}
    private static boolean legacyNumber(ProductMeasurementProjection.Declared definition){return definition!=null&&LEGACY_NUMBER.equals(definition.valueType());}
    private static String displayType(ProductMeasurementProjection.Declared definition){return definition==null||legacyNumber(definition)?"NUMBER":definition.valueType();}
    private static Raw raw(ResultSet rs)throws SQLException{return new Raw(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getObject(8,OffsetDateTime.class),rs.getObject(9,OffsetDateTime.class),rs.getObject(10,OffsetDateTime.class),rs.getString(11));}
    private static String nonblank(String value){return value==null||value.isBlank()?null:value;}
    private JsonNode parseParams(String value){try{return sourceJson.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){return null;}}
    private static boolean source(String platform,JsonNode root){return "hikvision_firecloud".equals(platform)&&root!=null&&"HIKVISION_FIRECLOUD".equals(root.path("source").asText());}
    private static String sourceUnit(JsonNode root,String metric){return nonblank(root.path("source_metrics").path(metric).path("unit").asText(null));}
    private static boolean isBatterySentinel(String metric,JsonNode value){
        if(!"battery".equals(metric)||value==null||value.isNull())return false;
        try{return new BigDecimal(value.asText()).compareTo(BigDecimal.valueOf(255))==0;}
        catch(NumberFormatException ignored){return false;}
    }
    private static BigDecimal metricNumber(String metric,JsonNode value,boolean legacyNumber){
        BigDecimal number;
        if(value!=null&&value.isNumber())number=value.decimalValue();
        else if(value!=null&&value.isTextual()&&(legacyNumber||NUMERIC_STRING_METRICS.contains(metric))&&value.textValue().length()<=64){
            try{number=new BigDecimal(value.textValue());}catch(NumberFormatException ignored){return null;}
        } else return null;
        if(number.precision()>64||Math.abs((long)number.scale())>64)return null;
        return number;
    }
    private Sample sample(Raw r,Map<String,ProductMeasurementProjection.Declared> declared,String metric){
        JsonNode root=parseParams(r.params());var readings=new ArrayList<Reading>();boolean source=source(r.platform(),root);
        if(root!=null){var fields=root.fields();while(fields.hasNext()){var field=fields.next();String key=field.getKey();JsonNode value=field.getValue();if(!validKey(key)||(metric!=null&&!metric.equals(key)))continue;
            var definition=declared.get(key);if(!METRICS.contains(key)&&definition==null&&!(source&&root.path("source_metrics").has(key)))continue;
            if(isBatterySentinel(key,value))continue;
            String type=displayType(definition),text;
            if("NUMBER".equals(type)){BigDecimal number=metricNumber(key,value,legacyNumber(definition));if(number==null)continue;text=number.toPlainString();}
            else if("STRING".equals(type)){if(!value.isTextual()||value.textValue().length()>com.fireplatform.product.ProductLimits.MAX_RAW_STRING)continue;text=value.textValue();}
            else {if(!value.isBoolean())continue;text=Boolean.toString(value.booleanValue());}
            boolean genericBattery="GENERIC_INGEST".equals(r.platform())&&"battery".equals(key);
            String unit=genericBattery?"%":source?sourceUnit(root,key):definition==null?null:definition.unit();
            String label=source?root.path("source_metrics").path(key).path("label").asText(definition==null?LABELS.getOrDefault(key,key):definition.label()):definition==null?LABELS.getOrDefault(key,key):definition.label();
            readings.add(new Reading(key,text,unit,unit==null?"UNKNOWN":"DECLARED",label,type,r.observed(),r.received(),r.created()));
        }}
        readings.sort(Comparator.comparing(Reading::metric));
        return new Sample(r.id(),r.tenant(),r.device(),r.name(),r.address(),r.observed(),r.received(),r.created(),List.copyOf(readings));
    }
}
