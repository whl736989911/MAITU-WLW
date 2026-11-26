package com.fireplatform.bulkio;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.device.DeviceInputs;
import com.fireplatform.device.DeviceManagementService;
import com.fireplatform.device.DeviceModelDisplay;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.DeviceScopes;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.identity.UnitDirectoryCommands;
import com.fireplatform.identity.UnitDirectoryModels;
import com.fireplatform.legacyingest.facts.DeviceStatusComposer;
import com.fireplatform.notification.ContactPhoneStorage;
import com.fireplatform.notification.NotificationConfigCommands;
import com.fireplatform.notification.NotificationInputs;
import com.fireplatform.notification.NotificationPolicy;
import com.fireplatform.place.PlaceCommands;
import com.fireplatform.place.PlaceInputs;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Limits;
import com.fireplatform.shared.ScopeFilter;
import com.fireplatform.simcard.SimCardCommands;
import com.fireplatform.simcard.SimCardInputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Canonical command and scoped projection adapter for the original five master-data kinds. */
@Component
public class MasterDataTransferDomain implements DataTransferDomain {
    private static final List<String> DEVICE_IMPORT=List.of("name","device_eui","address","product_version_id","place_id","system_type","device_category");
    private static final List<String> UNIT_IMPORT=List.of("id","name","credit_code","region_id","unit_type","industry","map_address","detail_address","contact_name","contact_phone","remark","latitude","longitude","service_start","service_end","engineering_service_start","engineering_service_end","expected_revision");
    private static final List<String> PLACE_IMPORT=List.of("name","parent_place_id");
    private static final List<String> CONTACT_IMPORT=List.of("name","contact_type","phone","enabled");
    private static final List<String> SIM_IMPORT=List.of("id","iccid","carrier_name","replaceability","management_party","fee_responsibility","reported_operator_status","expires_on","evidence_at","source_note","expected_revision");
    private static final List<String> DEVICE_EXPORT=List.of("设备名称","设备编号","设备型号","产品名称","产品版本",
        "归属单位","归属位置","安装地址","所属系统","设备类别","链路诊断","来源火情","电池电量","电量状态","最后上报时间（北京时间）");
    private static final DateTimeFormatter DEVICE_EXPORT_TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss",Locale.ROOT)
        .withZone(ZoneId.of("Asia/Shanghai"));
    private static final List<String> UNIT_EXPORT=List.of("id","name","credit_code","region_id","unit_type","industry","map_address","detail_address","contact_name","contact_phone","remark","latitude","longitude","service_start","service_end","engineering_service_start","engineering_service_end","revision");
    private static final List<String> PLACE_EXPORT=List.of("id","name","parent_place_id","revision");
    private static final List<String> CONTACT_EXPORT=List.of("id","name","contact_type","phone_masked","enabled","revision");
    private static final List<String> SIM_EXPORT=List.of("id","iccid","carrier_name","replaceability","management_party","fee_responsibility","reported_operator_status","expires_on","evidence_at","source_note","revision");
    private static final Set<String> MANAGEMENT=Set.of("management");
    private static final Set<String> TENANT_AUDIENCES=Set.of("management","customer");
    private final JdbcTemplate jdbc;
    private final DeviceManagementService devices;
    private final UnitDirectoryCommands units;
    private final PlaceCommands places;
    private final NotificationConfigCommands contacts;
    private final SimCardCommands simCards;
    private final ScopeFilter scope;
    private final DeviceScopes deviceScopes;
    private final com.fireplatform.configuration.OfflinePolicySql offlinePolicy;
    private final DatabaseTime time;
    private final jakarta.validation.Validator validator;
    private final ContactPhoneStorage contactPhones;
    private final DeviceStatusComposer deviceStatuses;
    public MasterDataTransferDomain(JdbcTemplate jdbc,DeviceManagementService devices,UnitDirectoryCommands units,PlaceCommands places,NotificationConfigCommands contacts,SimCardCommands simCards,ScopeFilter scope,DeviceScopes deviceScopes,com.fireplatform.configuration.OfflinePolicySql offlinePolicy,DatabaseTime time,jakarta.validation.Validator validator,ContactPhoneStorage contactPhones,DeviceStatusComposer deviceStatuses) {
        this.jdbc=jdbc;this.devices=devices;this.units=units;this.places=places;this.contacts=contacts;this.simCards=simCards;this.scope=scope;this.deviceScopes=deviceScopes;this.offlinePolicy=offlinePolicy;this.time=time;this.validator=validator;
        this.contactPhones=contactPhones;
        this.deviceStatuses=deviceStatuses;
    }


    @Override public List<Kind> kinds(){return List.of(
        new Kind("DEVICES",DEVICE_IMPORT,"device:read","device:write",true,true,true,TENANT_AUDIENCES,"MASTER_DATA"),
        new Kind("UNITS",UNIT_IMPORT,"units:read","units:write",true,true,false,MANAGEMENT,"MASTER_DATA"),
        new Kind("PLACES",PLACE_IMPORT,"units:read","units:write",true,true,true,TENANT_AUDIENCES,"MASTER_DATA"),
        new Kind("CONTACTS",CONTACT_IMPORT,"notification-config:read","notification-config:write",true,true,true,MANAGEMENT,"MASTER_DATA"),
        new Kind("SIM_CARDS",SIM_IMPORT,"sim-cards:read","sim-cards:write",true,true,true,MANAGEMENT,"MASTER_DATA"));}

    @Override public List<String> validate(String kind,Map<String,String> row,AuthService.Identity actor,String tenant) {
        var errors=new ArrayList<String>();for(String column:importColumns(kind))if(!row.containsKey(column))errors.add("缺少字段："+column);
        switch(kind){
            case "DEVICES"->validateDevice(row,actor,tenant,errors);
            case "UNITS"->{required(row,"name",32,errors);if(present(row,"id"))required(row,"expected_revision",32,errors);for(String field:List.of("latitude","longitude")){String v=row.get(field);if(v!=null&&!v.isBlank())try{Double.parseDouble(v);}catch(NumberFormatException invalid){errors.add(field+"必须是数字");}}for(String field:List.of("service_start","service_end","engineering_service_start","engineering_service_end")){String v=row.get(field);if(v!=null&&!v.isBlank())try{LocalDate.parse(v);}catch(RuntimeException invalid){errors.add(field+"日期无效");}}}
            case "PLACES"->errors.addAll(inputErrors(new PlaceInputs.Create(row.get("name"),blankNull(row.get("parent_place_id")))));
            case "CONTACTS"->{try{NotificationPolicy.text(row.get("name"),Limits.NOTIFICATION_NAME_MAX);NotificationPolicy.choice(row.get("contact_type"),NotificationPolicy.TYPES);contactPhones.normalize(row.get("phone"));}catch(ApiError invalid){errors.add(invalid.getMessage());}if(!Set.of("true","false").contains(row.getOrDefault("enabled","")))errors.add("enabled必须为true或false");}
            case "SIM_CARDS"->errors.addAll(validateSim(row));
            default->throw ApiError.bad("该类型不支持导入");
        }
        return List.copyOf(errors);
    }

    @Override public String operation(String kind,Map<String,String> row){return (kind.equals("UNITS")||kind.equals("SIM_CARDS"))&&present(row,"id")?"UPDATE":"CREATE";}

    @Override public String apply(String kind,Map<String,String> c,AuthService.Identity actor,String tenant,String reason,String jobId,int rowNumber) {
        String idem="data-transfer:"+jobId+":"+rowNumber,request="data-transfer";
        return switch(kind){
            case "DEVICES"->{var result=devices.register(actor,tenant,idem,deviceInput(c),request,actor.audience());yield result.id();}
            case "UNITS"->{UnitDirectoryModels.UnitDetails result;if(!present(c,"id")){result=units.create(actor,idem,new UnitDirectoryModels.UnitCreate(c.get("name"),blankNull(c.get("credit_code")),blankNull(c.get("region_id")),blankNull(c.get("unit_type")),blankNull(c.get("industry")),blankNull(c.get("map_address")),blankNull(c.get("detail_address")),blankNull(c.get("contact_name")),blankNull(c.get("contact_phone")),blankNull(c.get("remark")),decimal(c.get("latitude")),decimal(c.get("longitude")),date(c.get("service_start")),date(c.get("service_end")),date(c.get("engineering_service_start")),date(c.get("engineering_service_end")),null,null,null,null),request);}else{result=units.update(actor,c.get("id"),idem,new UnitDirectoryModels.UnitWrite(c.get("name"),blankNull(c.get("credit_code")),blankNull(c.get("region_id")),blankNull(c.get("unit_type")),blankNull(c.get("industry")),blankNull(c.get("map_address")),blankNull(c.get("detail_address")),blankNull(c.get("contact_name")),blankNull(c.get("contact_phone")),blankNull(c.get("remark")),decimal(c.get("latitude")),decimal(c.get("longitude")),date(c.get("service_start")),date(c.get("service_end")),date(c.get("engineering_service_start")),date(c.get("engineering_service_end")),Long.parseLong(c.get("expected_revision"))),request);}yield result.id();}
            case "PLACES"->{var result=places.create(actor,tenant,new PlaceInputs.Create(c.get("name"),blankNull(c.get("parent_place_id"))),request,actor.audience());yield result.id();}
            case "CONTACTS"->{var input=new NotificationInputs.Contact(c.get("name"),c.get("contact_type"),c.get("phone"),null,Boolean.valueOf(c.get("enabled")),null,reason);@SuppressWarnings("unchecked") var result=(Map<String,Object>)contacts.contact(actor,tenant,null,idem,input,request);yield Objects.toString(result.get("id"));}
            case "SIM_CARDS"->{boolean update=present(c,"id");var input=new SimCardInputs.Metadata(update?null:SimCardInputs.iccid(c.get("iccid")),SimCardInputs.carrier(c.get("carrier_name")),SimCardInputs.choice(c.get("replaceability"),SimCardInputs.REPLACEABILITY),SimCardInputs.choice(c.get("management_party"),SimCardInputs.PARTY),SimCardInputs.choice(c.get("fee_responsibility"),SimCardInputs.PARTY),SimCardInputs.choice(c.get("reported_operator_status"),SimCardInputs.REPORTED_STATUS),SimCardInputs.date(blankNull(c.get("expires_on"))),SimCardInputs.evidence(blankNull(c.get("evidence_at"))),SimCardInputs.note(c.get("source_note")),update?Long.parseLong(c.get("expected_revision")):null);var saved=update?simCards.edit(actor,tenant,c.get("id"),idem,input,request):simCards.create(actor,tenant,idem,input,request);yield saved.id();}
            default->throw ApiError.bad("该类型不支持导入");
        };
    }

    private static DeviceInputs.Register deviceInput(Map<String,String> row) {
        return new DeviceInputs.Register(row.get("name"),row.get("device_eui"),row.get("address"),row.get("place_id"),row.get("product_version_id"),row.get("system_type"),row.get("device_category"));
    }

    private void validateDevice(Map<String,String> row,AuthService.Identity actor,String tenant,List<String> errors) {
        var input=deviceInput(row);
        errors.addAll(inputErrors(input));
        String placeId=input.placeId();
        if(placeId==null||placeId.isBlank()||placeId.length()>64)return;

        var access=deviceScopes.placePredicate(actor.user().id(),"p.tenant_id","p.id");
        var args=new ArrayList<Object>(2+access.bindings().size());
        args.add(placeId);
        args.add(tenant);
        args.addAll(access.bindings());
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM places p WHERE p.id=? AND p.tenant_id=? AND p.archived_at IS NULL"
            +access.and(),Long.class,args.toArray());
        if(count==0)errors.add("place_id: 安装位置不可用或不在授权范围内");
    }
    private List<String> inputErrors(Object input) {
        return validator.validate(input).stream().map(error->error.getPropertyPath()+": "+error.getMessage()).sorted().toList();
    }

    @Override public ExportData export(String kind,AuthService.Identity actor,String tenant,Map<String,String> filters,List<String> selectedIds) {
        Set<String> additional=switch(kind){case "UNITS"->Set.of("keyword","status");case "PLACES"->Set.of("keyword");case "DEVICES"->Set.of("keyword","field","place_id","tenant_id","connectivity","physical_state","integration_state","system_type","device_category","battery_status","customer_status");default->Set.of();};
        ScopeFilter.Selection selection=DataTransferFilters.selection(filters,additional);List<String> columns=exportColumns(kind);
        if(selection.empty())return new ExportData(columns,List.of());
        if("DEVICES".equals(kind)) {
            var ids=DataTransferFilters.selectedIds(selectedIds);
            if(selectedIds!=null&&ids.isEmpty())return new ExportData(columns,List.of());
            var rows=exportDevices(actor.user().id(),tenant,selection,filters,ids);
            if(selectedIds!=null&&rows.size()!=ids.size())throw ApiError.forbidden("勾选记录不存在或不在当前授权及筛选范围内");
            return new ExportData(columns,rows);
        }
        String actorId=actor.user().id();List<List<String>> rows=switch(kind){
            case "UNITS"->exportUnits(actorId,tenant,selection,filters);
            case "PLACES"->exportPlaces(actorId,tenant,selection,filters);
            case "CONTACTS"->exportContacts(actorId,tenant,selection);
            case "SIM_CARDS"->exportSim(actorId,tenant,selection);
            default->throw ApiError.bad("该类型不支持导出");
        };
        return DataTransferFilters.selected(new ExportData(columns,rows),selectedIds);
    }

    private List<List<String>> exportDevices(String actor,String tenant,ScopeFilter.Selection selection,Map<String,String> filters,List<String> selectedIds){
        var access=deviceScopes.predicate(actor,"d.tenant_id","d.id");var tree=scope.devices(actor,"d",tenant,selection);var args=new ArrayList<Object>();args.add(time.now());args.addAll(access.bindings());args.addAll(tree.bindings());var extra=new StringBuilder();
        String keyword=filter(filters,"keyword");String field=filter(filters,"field");if(field!=null&&!Set.of("eui","name","model","address").contains(field))throw ApiError.bad("设备搜索字段无效");
        if(keyword!=null){if(keyword.length()>200)throw ApiError.bad("设备关键字过长");String like="%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";String[] fields=field==null?new String[]{"d.name","d.device_eui",DeviceModelDisplay.SQL,"d.address"}:new String[]{switch(field){case "eui"->"d.device_eui";case "name"->"d.name";case "model"->DeviceModelDisplay.SQL;default->"d.address";}};extra.append(" AND (");for(int i=0;i<fields.length;i++){if(i>0)extra.append(" OR ");extra.append("LOWER(COALESCE(").append(fields[i]).append(",'')) LIKE LOWER(?) ESCAPE '!'");args.add(like);}extra.append(")");}
        addEquality(extra,args,filters,"tenant_id","d.tenant_id",256);addEquality(extra,args,filters,"place_id","d.place_id",256);
        addEnum(extra,args,filters,"connectivity",offlinePolicy.connectivity(),Set.of("ONLINE","OFFLINE","UNKNOWN","UNACTIVATED"));
        addEnum(extra,args,filters,"physical_state","d.physical_state",Set.of("FIRE","NORMAL","UNKNOWN"));
        addEnum(extra,args,filters,"integration_state","d.integration_state",Set.of("CONNECTED","PENDING_INTEGRATION","LEGACY_UNVERIFIED","RETIRED_SIMULATION"));
        addMetadata(extra,args,filters,"system_type","d.system_type");addMetadata(extra,args,filters,"device_category","d.device_category");
        if(!selectedIds.isEmpty()){
            extra.append(" AND d.id IN (").append(String.join(",",java.util.Collections.nCopies(selectedIds.size(),"?"))).append(')');
            args.addAll(selectedIds);
        }
        String threshold="COALESCE(dbo.threshold,pv.battery_low_threshold)",comparator="COALESCE(dbo.comparator,pv.battery_low_comparator)";
        String battery="CASE WHEN "+threshold+" IS NULL THEN 'NOT_CONFIGURED' WHEN d.battery_percent IS NULL THEN 'NO_DATA' WHEN (("+comparator+"='LT' AND d.battery_percent<"+threshold+") OR ("+comparator+"='LTE' AND d.battery_percent<="+threshold+")) THEN 'LOW' ELSE 'NORMAL' END";
        String batteryStatus=filter(filters,"battery_status");if(batteryStatus!=null){if(!Set.of("LOW","NORMAL","NO_DATA","NOT_CONFIGURED").contains(batteryStatus))throw ApiError.bad("电量状态筛选无效");extra.append(" AND (").append(battery).append(")=?");args.add(batteryStatus);}
        var statuses=DeviceStatusComposer.parseCustomerStatuses(filter(filters,"customer_status"));
        String fields="""
            d.id,d.name,d.device_eui,d.address,d.product_name,d.version_number,d.physical_state,
            d.last_message_at,d.battery_percent,d.system_type,d.device_category,t.name AS unit_name,p.name AS place_name,
            """+DeviceModelDisplay.SQL+" AS display_model,"+offlinePolicy.connectivity()+" AS computed_connectivity,"+battery+" AS battery_status";
        String from=" FROM devices d"+offlinePolicy.connectivityJoins()+"""
             LEFT JOIN product_versions pv ON pv.id=d.product_version_id
             LEFT JOIN device_battery_threshold_overrides dbo ON dbo.device_id=d.id
             JOIN tenants t ON t.id=d.tenant_id
             LEFT JOIN places p ON p.tenant_id=d.tenant_id AND p.id=d.place_id
             WHERE d.is_simulated=FALSE AND d.archived_at IS NULL
            """+access.and()+tree.and()+extra;
        String sql=statuses.isEmpty() ? "SELECT "+fields+from+" ORDER BY d.id LIMIT 10001"
            : "SELECT q.* FROM (SELECT "+fields+deviceStatuses.columns()+from+") q"
                +DeviceStatusComposer.customerStatusWhere(statuses)+" ORDER BY q.id LIMIT 10001";
        args.addAll(statuses);
        return jdbc.query(sql,(rs,n)->deviceExportRow(rs),args.toArray());
    }

    private static List<String> deviceExportRow(ResultSet rs) throws SQLException {
        Long version=rs.getObject("version_number",Long.class);
        var battery=rs.getBigDecimal("battery_percent");
        var reportTime=rs.getObject("last_message_at",OffsetDateTime.class);
        String connectivity=switch(rs.getString("computed_connectivity")){
            case "ONLINE" -> "在线";
            case "OFFLINE" -> "离线";
            case "UNACTIVATED" -> "未激活";
            default -> "状态未确认";
        };
        String fireState=switch(rs.getString("physical_state")){
            case "FIRE" -> "火警";
            case "NORMAL" -> "正常";
            case null, default -> "未确认";
        };
        String batteryStatus=switch(rs.getString("battery_status")){
            case "LOW" -> "低电量";
            case "NORMAL" -> "电量正常";
            case "NO_DATA" -> "无电量数据";
            case "NOT_CONFIGURED" -> "未配置阈值";
            default -> "未确认";
        };
        return List.of(text(rs.getString("name")),text(rs.getString("device_eui")),text(rs.getString("display_model")),
            text(rs.getString("product_name")),version==null?"未绑定":"v"+version,rs.getString("unit_name"),
            Objects.toString(rs.getString("place_name"),"未指定位置"),text(rs.getString("address")),
            text(rs.getString("system_type")),text(rs.getString("device_category")),connectivity,fireState,
            battery==null?"未上报":battery.stripTrailingZeros().toPlainString()+"%",batteryStatus,
            reportTime==null?"未上报":DEVICE_EXPORT_TIME.format(reportTime));
    }
    private static String filter(Map<String,String> filters,String key){if(filters==null)return null;String value=filters.get(key);return value==null||value.isBlank()?null:value.trim();}
    private static void addEquality(StringBuilder sql,List<Object> args,Map<String,String> filters,String key,String column,int max){String value=filter(filters,key);if(value!=null){DataTransferFilters.selectedIds(List.of(value));if(value.length()>max)throw ApiError.bad("设备范围筛选无效");sql.append(" AND ").append(column).append("=?");args.add(value);}}
    private static void addEnum(StringBuilder sql,List<Object> args,Map<String,String> filters,String key,String expression,Set<String> allowed){String value=filter(filters,key);if(value!=null){if(!allowed.contains(value))throw ApiError.bad("设备筛选状态无效");sql.append(" AND ").append(expression).append("=?");args.add(value);}}
    private static void addMetadata(StringBuilder sql,List<Object> args,Map<String,String> filters,String key,String column){String value=filter(filters,key);if(value!=null){if(value.length()>80||value.chars().anyMatch(Character::isISOControl))throw ApiError.bad("设备元数据筛选值无效");sql.append(" AND ").append(column).append("=?");args.add(value);}}
    private List<List<String>> exportUnits(String actor,String tenant,ScopeFilter.Selection selection,Map<String,String> filters){var clause=scope.units(actor,"t","id",tenant,selection);var args=new ArrayList<Object>(clause.bindings());String extra="";String keyword=filters==null?null:filters.get("keyword");if(keyword!=null&&!keyword.isBlank()){if(keyword.length()>200)throw ApiError.bad("单位关键字过长");extra+=" AND (LOWER(COALESCE(t.name,'')) LIKE LOWER(?) OR LOWER(COALESCE(t.credit_code,'')) LIKE LOWER(?) OR LOWER(COALESCE(t.map_address,'')) LIKE LOWER(?))";String like="%"+keyword.trim()+"%";args.add(like);args.add(like);args.add(like);}String status=filters==null?null:filters.get("status");if(status!=null&&!status.isBlank()){if(!Set.of("enabled","disabled").contains(status))throw ApiError.bad("单位状态筛选无效");extra+=" AND t.enabled=?";args.add("enabled".equals(status));}return jdbc.query("SELECT t.id,t.name,t.credit_code,t.region_id,t.unit_type,t.industry,t.map_address,t.detail_address,t.contact_name,t.contact_phone,t.remark,t.latitude,t.longitude,t.service_start,t.service_end,t.engineering_service_start,t.engineering_service_end,t.revision FROM tenants t WHERE 1=1"+clause.and()+extra+" ORDER BY t.id LIMIT 10001",(rs,n)->Arrays.asList(text(rs.getString(1)),text(rs.getString(2)),text(rs.getString(3)),text(rs.getString(4)),text(rs.getString(5)),text(rs.getString(6)),text(rs.getString(7)),text(rs.getString(8)),text(rs.getString(9)),text(rs.getString(10)),text(rs.getString(11)),text(rs.getString(12)),text(rs.getString(13)),text(rs.getString(14)),text(rs.getString(15)),text(rs.getString(16)),text(rs.getString(17)),Long.toString(rs.getLong(18))),args.toArray());}

    private List<List<String>> exportPlaces(String actor,String tenant,ScopeFilter.Selection selection,Map<String,String> filters){
        var access=scope.units(actor,"p","tenant_id",tenant,ScopeFilter.Selection.all());var args=new ArrayList<Object>(access.bindings());String selected="";
        if(!selection.tenantIds().isEmpty()||!selection.placeIds().isEmpty()){
            var alternatives=new ArrayList<String>();if(!selection.tenantIds().isEmpty()){alternatives.add("p.tenant_id IN ("+String.join(",",java.util.Collections.nCopies(selection.tenantIds().size(),"?"))+")");args.addAll(selection.tenantIds());}
            if(!selection.placeIds().isEmpty()){alternatives.add("p.id IN (WITH RECURSIVE transfer_place_tree(id,tenant_id) AS (SELECT root.id,root.tenant_id FROM places root WHERE root.id IN ("+String.join(",",java.util.Collections.nCopies(selection.placeIds().size(),"?"))+") UNION ALL SELECT child.id,child.tenant_id FROM places child JOIN transfer_place_tree parent ON child.parent_place_id=parent.id AND child.tenant_id=parent.tenant_id) SELECT id FROM transfer_place_tree)");args.addAll(selection.placeIds());}
            selected=" AND ("+String.join(" OR ",alternatives)+")";
        }
        String keyword=filters==null?null:filters.get("keyword");if(keyword!=null&&!keyword.isBlank()){if(keyword.length()>200)throw ApiError.bad("位置关键字过长");selected+=" AND (LOWER(COALESCE(p.name,'')) LIKE LOWER(?) OR LOWER(COALESCE(p.legacy_place_id,'')) LIKE LOWER(?))";String like="%"+keyword.trim()+"%";args.add(like);args.add(like);}
        return jdbc.query("SELECT p.id,p.name,p.parent_place_id,p.revision FROM places p WHERE p.archived_at IS NULL"+access.and()+selected+" ORDER BY p.id LIMIT 10001",(rs,n)->List.of(text(rs.getString(1)),text(rs.getString(2)),text(rs.getString(3)),Long.toString(rs.getLong(4))),args.toArray());
    }

    private List<List<String>> exportContacts(String actor,String tenant,ScopeFilter.Selection selection){var clause=scope.units(actor,"c","tenant_id",tenant,selection);return jdbc.query("SELECT c.id,c.name,c.contact_type,c.phone_masked,c.enabled,c.revision FROM notification_contacts c WHERE 1=1"+clause.and()+" ORDER BY c.id LIMIT 10001",(rs,n)->List.of(text(rs.getString(1)),text(rs.getString(2)),text(rs.getString(3)),text(rs.getString(4)),Boolean.toString(rs.getBoolean(5)),Long.toString(rs.getLong(6))),clause.bindings().toArray());}
    private List<List<String>> exportSim(String actor,String tenant,ScopeFilter.Selection selection){var clause=scope.units(actor,"s","tenant_id",tenant,selection);return jdbc.query("SELECT s.id,s.iccid,s.carrier_name,s.replaceability,s.management_party,s.fee_responsibility,s.reported_operator_status,s.expires_on,s.evidence_at,s.source_note,s.revision FROM sim_cards s WHERE 1=1"+clause.and()+" ORDER BY s.id LIMIT 10001",(rs,n)->Arrays.asList(text(rs.getString(1)),text(rs.getString(2)),text(rs.getString(3)),text(rs.getString(4)),text(rs.getString(5)),text(rs.getString(6)),text(rs.getString(7)),text(rs.getString(8)),text(rs.getString(9)),text(rs.getString(10)),Long.toString(rs.getLong(11))),clause.bindings().toArray());}

    private static List<String> importColumns(String kind){return switch(kind){case "DEVICES"->DEVICE_IMPORT;case "UNITS"->UNIT_IMPORT;case "PLACES"->PLACE_IMPORT;case "CONTACTS"->CONTACT_IMPORT;case "SIM_CARDS"->SIM_IMPORT;default->throw ApiError.bad("该类型不支持导入");};}
    private static List<String> exportColumns(String kind){return switch(kind){case "DEVICES"->DEVICE_EXPORT;case "UNITS"->UNIT_EXPORT;case "PLACES"->PLACE_EXPORT;case "CONTACTS"->CONTACT_EXPORT;case "SIM_CARDS"->SIM_EXPORT;default->throw ApiError.bad("该类型不支持导出");};}
    private static boolean present(Map<String,String> row,String key){return row.get(key)!=null&&!row.get(key).isBlank();}
    private static String value(Map<String,String> row,String key){return Objects.toString(row.get(key),"");}
    private static void required(Map<String,String> row,String key,int max,List<String> errors){String value=row.get(key);if(value==null||value.isBlank())errors.add(key+"不能为空");else if(value.length()>max)errors.add(key+"超过长度限制");}
    private static List<String> validateSim(Map<String,String> row){var errors=new ArrayList<String>();boolean update=present(row,"id");if(update){required(row,"expected_revision",32,errors);if(present(row,"iccid"))errors.add("已登记SIM不能通过导入修改ICCID");}else required(row,"iccid",128,errors);required(row,"carrier_name",128,errors);required(row,"replaceability",32,errors);required(row,"management_party",32,errors);required(row,"fee_responsibility",32,errors);required(row,"reported_operator_status",32,errors);required(row,"source_note",1000,errors);try{if(!update)SimCardInputs.iccid(row.get("iccid"));SimCardInputs.carrier(row.get("carrier_name"));SimCardInputs.choice(row.get("replaceability"),SimCardInputs.REPLACEABILITY);SimCardInputs.choice(row.get("management_party"),SimCardInputs.PARTY);SimCardInputs.choice(row.get("fee_responsibility"),SimCardInputs.PARTY);SimCardInputs.choice(row.get("reported_operator_status"),SimCardInputs.REPORTED_STATUS);SimCardInputs.date(blankNull(row.get("expires_on")));SimCardInputs.evidence(blankNull(row.get("evidence_at")));SimCardInputs.note(row.get("source_note"));if(update)Long.parseLong(row.get("expected_revision"));}catch(RuntimeException failure){errors.add(bounded(failure.getMessage()));}return List.copyOf(errors);}
    private static String bounded(String value){if(value==null||value.isBlank())return "处理失败";String safe=value.replaceAll("[\\r\\n\\t]"," ");return safe.substring(0,Math.min(500,safe.length()));}
    private static String blankNull(String value){return value==null||value.isBlank()?null:value;}
    private static String text(String value){return value==null?"":value;}
    private static Double decimal(String value){return blankNull(value)==null?null:Double.valueOf(value);}
    private static LocalDate date(String value){return blankNull(value)==null?null:LocalDate.parse(value);}
}
