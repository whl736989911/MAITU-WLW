package com.fireplatform.bulkio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.*;
import com.fireplatform.identity.AuthService;
import com.fireplatform.notification.*;
import com.fireplatform.product.*;
import com.fireplatform.shared.*;
import com.fireplatform.shared.DeviceAccessScope;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Import/export adapters for configuration changes; all writes are delegated to domain commands. */
@Component
public class ConfigurationDataTransferDomain implements DataTransferDomain {
    private static final Set<String> MANAGEMENT = Set.of("management");
    private static final Set<String> CUSTOMER = Set.of("customer");
    private static final List<String> PRODUCT_COLUMNS = List.of("id", "operation", "name", "manufacturer", "model", "description", "product_id", "base_version_id", "version_id", "expected_revision", "schema_json", "capabilities_json", "battery_low_threshold_json");
    private static final List<String> RULE_COLUMNS = List.of("id", "name", "event_scope", "receivers_json", "sms_escalation_interval_minutes", "voice_escalation_interval_minutes", "wechat_escalation_interval_minutes", "enabled");
    private static final List<String> POLICY_COLUMNS = List.of("id", "operation", "scope", "scope_id", "parameter_key", "value", "expected_revision");
    private static final List<String> CONTACT_COLUMNS = List.of("id", "name", "contact_type", "phone", "enabled", "expected_revision");
    private static final List<String> UNIT_RULE_COLUMNS = RULE_COLUMNS;
    private final ProductCommands products;
    private final ProductQueries productQueries;
    private final JsonPointerParser parser;
    private final ParameterCommands parameters;
    private final ParameterQueries parameterQueries;
    private final NotificationConfigCommands notifications;
    private final CustomerNotificationSettingsService customerNotifications;
    private final NotificationReads notificationReads;
    private final ContactPhoneStorage contactPhones;
    private final JdbcTemplate jdbc;
    private final ScopeFilter scope;
    private final DeviceAccessScope deviceAccess;
    private final ObjectMapper mapper;

    public ConfigurationDataTransferDomain(ProductCommands products, ProductQueries productQueries, JsonPointerParser parser,
            ParameterCommands parameters, ParameterQueries parameterQueries, NotificationConfigCommands notifications,
            CustomerNotificationSettingsService customerNotifications, NotificationReads notificationReads,
            JdbcTemplate jdbc, ScopeFilter scope, DeviceAccessScope deviceAccess, ObjectMapper mapper, ContactPhoneStorage contactPhones) {
        this.products=products; this.productQueries=productQueries; this.parser=parser; this.parameters=parameters;
        this.parameterQueries=parameterQueries; this.notifications=notifications; this.customerNotifications=customerNotifications;
        this.notificationReads=notificationReads; this.jdbc=jdbc; this.scope=scope; this.deviceAccess=deviceAccess; this.mapper=mapper;
        this.contactPhones=contactPhones;
    }

    @Override public List<Kind> kinds() {
        return List.of(
            new Kind("PRODUCT_DRAFTS", PRODUCT_COLUMNS, "products:read", "products:write", true, true, false, MANAGEMENT, "DRAFT_ONLY"),
            new Kind("RULE_DRAFTS", RULE_COLUMNS, "notification-config:read", "notification-config:write", true, true, true, MANAGEMENT, "DISABLED_DRAFT"),
            new Kind("POLICY_DRAFTS", POLICY_COLUMNS, "parameters:read", "parameters:write", true, true, true, MANAGEMENT, "APPLY_ON_CONFIRM"),
            new Kind("UNIT_CONTACTS", CONTACT_COLUMNS, "unit-notifications:read", "unit-notifications:write", true, true, true, CUSTOMER, "MASTER_DATA"),
            new Kind("UNIT_NOTIFICATION_RULES", UNIT_RULE_COLUMNS, "unit-notifications:read", "unit-notifications:write", true, true, true, CUSTOMER, "DISABLED_DRAFT")
        );
    }

    @Override public List<String> validate(String kind, Map<String,String> c, AuthService.Identity actor, String tenant) {
        try {
            switch (kind) {
                case "PRODUCT_DRAFTS" -> validateProduct(c);
                case "RULE_DRAFTS" -> { if(!"false".equalsIgnoreCase(required(c,"enabled")))throw ApiError.bad("草稿规则必须保持disabled"); validateRule(c,actor,tenant,false); }
                case "UNIT_NOTIFICATION_RULES" -> { if(!"false".equalsIgnoreCase(required(c,"enabled")))throw ApiError.bad("草稿规则必须保持disabled"); validateRule(c,actor,tenant,true); customerNotifications.rules(actor,1,1,"","UNIT",actor.user().tenantId()); }
                case "UNIT_CONTACTS" -> { customerTenant(actor,tenant); contactInput(c); customerNotifications.contacts(actor,1,1,""); }
                default -> throw ApiError.bad("该类型不支持导入");
            }
            return List.of();
        } catch (RuntimeException e) { return List.of(message(e)); }
    }

    @Override public String operation(String kind, Map<String,String> c) {
        return switch(kind) {
            case "PRODUCT_DRAFTS" -> required(c,"operation").toUpperCase(Locale.ROOT);
            case "POLICY_DRAFTS" -> required(c,"operation").toUpperCase(Locale.ROOT);
            case "UNIT_CONTACTS" -> blank(c.get("id")) ? "CREATE" : "UPDATE";
            case "RULE_DRAFTS", "UNIT_NOTIFICATION_RULES" -> "CREATE_DISABLED_DRAFT";
            default -> throw ApiError.bad("该类型不支持导入");
        };
    }

    @Override public String apply(String kind, Map<String,String> c, AuthService.Identity actor, String tenant,
            String reason, String jobId, int rowNumber) {
        String key="data-transfer:"+jobId+":"+rowNumber;
        return switch(kind) {
            case "PRODUCT_DRAFTS" -> applyProduct(c,actor,key);
            case "RULE_DRAFTS" -> { if(blank(tenant))throw ApiError.bad("规则草稿必须指定单位"); yield applyRule(c,actor,tenant,reason,key,false); }
            case "POLICY_DRAFTS" -> applyPolicy(c,actor,tenant,reason,key);
            case "UNIT_CONTACTS" -> applyContact(c,actor,tenant,key,reason);
            case "UNIT_NOTIFICATION_RULES" -> { customerTenant(actor,tenant); yield applyRule(c,actor,tenant,reason,key,true); }
            default -> throw ApiError.bad("该类型不支持导入");
        };
    }

    private String applyProduct(Map<String,String> c, AuthService.Identity actor, String key) {
        String op=required(c,"operation").toUpperCase(Locale.ROOT);
        return switch(op) {
            case "CREATE_PRODUCT" -> products.create(actor,key,new ProductInputs.CreateProduct(required(c,"name"),required(c,"manufacturer"),required(c,"model"),required(c,"description")),"data-transfer").id();
            case "CREATE_VERSION" -> products.createVersion(actor,required(c,"product_id"),key,new ProductInputs.CreateVersion(blankNull(c.get("base_version_id"))),"data-transfer").id();
            case "EDIT_VERSION" -> products.edit(actor,required(c,"version_id"),key,new ProductInputs.EditVersion(longValue(c,"expected_revision"),json(c,"schema_json"),json(c,"capabilities_json"),battery(c.get("battery_low_threshold_json"))),"data-transfer").id();
            default -> throw ApiError.bad("operation仅支持CREATE_PRODUCT、CREATE_VERSION或EDIT_VERSION");
        };
    }

    private void validateProduct(Map<String,String> c) {
        switch(required(c,"operation").toUpperCase(Locale.ROOT)) {
            case "CREATE_PRODUCT" -> { if(!blank(c.get("id")))throw ApiError.bad("创建产品不能指定ID"); text(c,"name",200); text(c,"manufacturer",100); text(c,"model",100); text(c,"description",2000); }
            case "CREATE_VERSION" -> { if(!blank(c.get("id")))throw ApiError.bad("创建版本不能指定ID"); var product=productQueries.product(required(c,"product_id"),false); if(!blank(c.get("base_version_id"))&&!product.id().equals(productQueries.version(c.get("base_version_id"),false).productId()))throw ApiError.conflict("PRODUCT_MISMATCH","基础版本必须属于当前产品"); }
            case "EDIT_VERSION" -> { String id=required(c,"version_id"); if(!blank(c.get("id"))&&!id.equals(c.get("id")))throw ApiError.bad("id必须与version_id一致"); var v=productQueries.version(id,false); if(!"DRAFT".equals(v.status()))throw ApiError.conflict("VERSION_IMMUTABLE","已发布版本及证据不可修改"); if(v.revision()!=longValue(c,"expected_revision"))throw ApiError.conflict("REVISION_CONFLICT","版本已更新，请刷新"); parser.validate(json(c,"schema_json"),json(c,"capabilities_json")); battery(c.get("battery_low_threshold_json")); }
            default -> throw ApiError.bad("operation仅支持CREATE_PRODUCT、CREATE_VERSION或EDIT_VERSION");
        }
    }

    private ProductInputs.BatteryThresholdInput battery(String source) {
        if(blank(source))return null;
        try { return mapper.readValue(source,ProductInputs.BatteryThresholdInput.class); }
        catch(Exception e){throw ApiError.bad("battery_low_threshold_json结构无效");}
    }

    private NotificationInputs.Rule ruleInput(Map<String,String> c,boolean customer) {
        JsonNode receiversNode=json(c,"receivers_json");
        if(!receiversNode.isArray())throw ApiError.bad("receivers_json必须是数组");
        List<NotificationInputs.Receiver> receivers=new ArrayList<>();
        for(JsonNode r:receiversNode)receivers.add(new NotificationInputs.Receiver(text(r,"channel"),text(r,"contact_id"),number(r,"level")));
        Long sms=nullableLong(c.get("sms_escalation_interval_minutes")),voice=nullableLong(c.get("voice_escalation_interval_minutes")),wechat=nullableLong(c.get("wechat_escalation_interval_minutes"));
        return new NotificationInputs.Rule(required(c,"name"),false,required(c,"event_scope"),receivers,sms,voice,wechat,null,"Data transfer draft");
    }
    private void validateRule(Map<String,String> c,AuthService.Identity actor,String tenant,boolean customer) {
        // IDs are export-only selectors; apply always creates a new disabled rule with id=null.
        var in=ruleInput(c,customer);
        applyRuleValidation(in);
        String owner=customer?customerTenant(actor,tenant):tenant;
        for(var receiver:in.receivers()) {
            var rows=jdbc.query("SELECT enabled FROM notification_contacts WHERE tenant_id=? AND id=?",(rs,n)->rs.getBoolean(1),owner,receiver.contactId());
            if(rows.isEmpty()||!rows.getFirst())throw ApiError.conflict("CONTACT_UNAVAILABLE","规则接收人不存在或已停用");
        }
    }
    private String customerTenant(AuthService.Identity actor,String tenant) {
        String own=actor.user().tenantId();
        if(!"customer".equals(actor.audience())||!"CUSTOMER".equals(actor.user().userType())||own==null||!own.equals(tenant))throw ApiError.forbidden("客户配置仅限当前登录单位");
        return own;
    }

    private void applyRuleValidation(NotificationInputs.Rule in) { NotificationConfigCommands.receivers(in); NotificationPolicy.text(in.name(),Limits.NOTIFICATION_NAME_MAX); NotificationPolicy.choice(in.eventScope(),List.of("FIRE","FAULT")); }
    private String applyRule(Map<String,String> c,AuthService.Identity actor,String tenant,String reason,String key,boolean customer) {
        if(!"false".equalsIgnoreCase(required(c,"enabled")))throw ApiError.bad("仅允许创建禁用草稿，不可更新已有规则");
        if(customer)customerTenant(actor,tenant);else if(blank(tenant))throw ApiError.bad("规则草稿必须指定单位");
        NotificationInputs.Rule parsed=ruleInput(c,customer);
        NotificationInputs.Rule command=new NotificationInputs.Rule(parsed.name(),false,parsed.eventScope(),parsed.receivers(),parsed.smsEscalationIntervalMinutes(),parsed.voiceEscalationIntervalMinutes(),parsed.wechatEscalationIntervalMinutes(),null,reason);
        return (customer?customerNotifications.rule(actor,null,key,command,"data-transfer"):notifications.rule(actor,tenant,null,key,command,"data-transfer")).get("id").toString();
    }

    private void validatePolicy(Map<String,String> c,AuthService.Identity actor,String tenant) {
        String op=required(c,"operation").toUpperCase(Locale.ROOT), scopeName=required(c,"scope"), id=required(c,"scope_id"), key=required(c,"parameter_key");
        if(!Set.of("SET","CLEAR").contains(op))throw ApiError.bad("operation仅支持SET或CLEAR");
        if(!"offline_threshold_minutes".equals(key))throw ApiError.bad("仅支持设备离线判定阈值");
        if(!Set.of("TENANT","PLACE","DEVICE").contains(scopeName))throw ApiError.bad("scope仅支持TENANT、PLACE或DEVICE");
        String stable=scopeName+":"+id+":"+key;
        if(!blank(c.get("id"))&&!stable.equals(c.get("id")))throw ApiError.bad("id与策略目标不匹配");
        parameterQueries.validate(actor.user().id(),tenant,scopeName,id,key,false);
        long rev=longValue(c,"expected_revision");var view=parameterQueries.view(actor.user().id(),tenant,scopeName,id,key);
        if(view.revision()!=rev)throw ApiError.conflict("REVISION_CONFLICT","覆盖已更新，请刷新后重开操作");
        if("SET".equals(op))longValue(c,"value"); else if(view.value()==null)throw ApiError.conflict("NO_OVERRIDE","当前没有可清除的覆盖");
    }
    private String applyPolicy(Map<String,String> c,AuthService.Identity actor,String tenant,String reason,String key) {
        validatePolicy(c,actor,tenant);
        String scopeName=c.get("scope"), id=c.get("scope_id"), parameter=c.get("parameter_key"), op=c.get("operation").toUpperCase(Locale.ROOT);
        if("SET".equals(op))return parameters.set(actor,tenant,scopeName,id,parameter,key,new ParameterInputs.SetValue(longValue(c,"value"),longValue(c,"expected_revision"),reason),"data-transfer").change().id();
        return parameters.clear(actor,tenant,scopeName,id,parameter,key,new ParameterInputs.Clear(longValue(c,"expected_revision"),reason),"data-transfer").change().id();
    }

    private void contactInput(Map<String,String> c) {
        NotificationPolicy.text(required(c,"name"),Limits.NOTIFICATION_NAME_MAX);NotificationPolicy.choice(required(c,"contact_type"),NotificationPolicy.TYPES);
        if(!blank(c.get("phone")))contactPhones.normalize(c.get("phone"));
        if(!Set.of("true","false").contains(required(c,"enabled")))throw ApiError.bad("enabled必须为true或false");
        if(blank(c.get("id"))) {
            if(blank(c.get("phone")))throw ApiError.bad("创建联系人必须明确填写号码");
            if(!blank(c.get("expected_revision")))throw ApiError.bad("创建联系人不能指定expected_revision");
        } else NotificationPolicy.number(nullableLong(c.get("expected_revision")),0,Long.MAX_VALUE);
    }
    private String applyContact(Map<String,String> c,AuthService.Identity actor,String tenant,String key,String reason) {
        customerTenant(actor,tenant);
        NotificationInputs.Contact input=new NotificationInputs.Contact(required(c,"name"),required(c,"contact_type"),blankNull(c.get("phone")),null,Boolean.valueOf(required(c,"enabled")),nullableLong(c.get("expected_revision")),reason);
        return customerNotifications.contact(actor,blankNull(c.get("id")),key,input,"data-transfer").get("id").toString();
    }

    @Override public ExportData export(String kind,AuthService.Identity actor,String tenant,Map<String,String> filters,List<String> selectedIds) {
        var selection=DataTransferFilters.selection(filters,Set.of());
        if(selection.empty()) {
            var descriptor=kinds().stream().filter(k->k.kind().equals(kind)).findFirst().orElseThrow(()->ApiError.bad("该类型不支持导出"));
            return new ExportData(descriptor.columns(),List.of());
        }
        if("PRODUCT_DRAFTS".equals(kind)&&(!selection.tenantIds().isEmpty()||!selection.placeIds().isEmpty()))
            throw ApiError.bad("产品草稿不支持单位或场所筛选");
        ExportData data=switch(kind) {
            case "PRODUCT_DRAFTS" -> exportProducts();
            case "RULE_DRAFTS" -> exportRules(actor,tenant,selection,false);
            case "POLICY_DRAFTS" -> exportPolicies(actor,tenant,selection);
            case "UNIT_CONTACTS" -> exportContacts(actor,tenant,selection);
            case "UNIT_NOTIFICATION_RULES" -> exportRules(actor,tenant,selection,true);
            default -> throw ApiError.bad("该类型不支持导出");
        };
        if(selectedIds==null)return data;
        List<String> requested=DataTransferFilters.selectedIds(selectedIds);
        if(requested.isEmpty())return new ExportData(data.columns(),List.of());
        int idColumn=data.columns().indexOf("id");
        if(idColumn<0)throw new IllegalStateException("勾选导出必须提供稳定id列");
        var remaining=new HashSet<>(requested);var rows=new ArrayList<List<String>>();
        for(var row:data.rows())if(remaining.remove(row.get(idColumn)))rows.add(row);
        if(!remaining.isEmpty())throw ApiError.forbidden("勾选记录不存在或不在当前授权及筛选范围内");
        return new ExportData(data.columns(),rows);
    }
    private ExportData exportProducts() {
        var rows=new ArrayList<List<String>>();
        List<String> ids=jdbc.query("SELECT id FROM products ORDER BY id",(rs,n)->rs.getString(1));
        for(String id:ids)for(var v:productQueries.versions(id))if("DRAFT".equals(v.status())) {
            var p=productQueries.product(id,false);
            rows.add(Arrays.asList(v.id(),"EDIT_VERSION",p.name(),p.manufacturer(),p.model(),p.description(),p.id(),"",v.id(),Long.toString(v.revision()),v.schema().toString(),v.capabilities().toString(),v.batteryLowThreshold()==null?"":mapper.valueToTree(v.batteryLowThreshold()).toString()));
        }
        return new ExportData(PRODUCT_COLUMNS,rows);
    }
    private ExportData exportRules(AuthService.Identity actor,String tenant,ScopeFilter.Selection selection,boolean customer) {
        if(customer) {
            String own=actor.user().tenantId();
            if(selection.empty())return new ExportData(UNIT_RULE_COLUMNS,List.of());
            requireCustomerSelection(actor,tenant,selection);
            var rows=new ArrayList<List<String>>();
            for(int page=1;;page++){var result=customerNotifications.rules(actor,page,100,"","UNIT",own);for(var r:result.items())if(Boolean.FALSE.equals(r.get("enabled")))rows.add(ruleRow(r));if(page*100>=result.total())break;}
            return new ExportData(UNIT_RULE_COLUMNS,rows);
        }
        var filter=scope.units(actor.user().id(),"r","tenant_id",tenant,selection);var args=new ArrayList<>(filter.bindings());
        List<Map<String,Object>> rules=jdbc.queryForList("SELECT r.id,r.tenant_id FROM notification_rules r WHERE r.legacy_mode IS NULL AND r.scope_type='UNIT' AND r.enabled=FALSE"+filter.and()+" ORDER BY r.id",args.toArray());
        var rows=new ArrayList<List<String>>(); for(var r:rules) { var detail=notificationReads.rule(Objects.toString(r.get("tenant_id")),Objects.toString(r.get("id"))); rows.add(ruleRow(detail)); }
        return new ExportData(RULE_COLUMNS,rows);
    }
    private List<String> ruleRow(Map<String,Object> r) {
        try {return List.of(Objects.toString(r.get("id"),""),Objects.toString(r.get("name"),""),Objects.toString(r.get("event_scope"),""),mapper.writeValueAsString(r.get("receivers")),Objects.toString(r.get("sms_escalation_interval_minutes"),"0"),Objects.toString(r.get("voice_escalation_interval_minutes"),"0"),Objects.toString(r.get("wechat_escalation_interval_minutes"),""),"false");} catch(Exception e){throw new IllegalStateException(e);}
    }
    private ExportData exportContacts(AuthService.Identity actor,String tenant,ScopeFilter.Selection selection) {
        if(!"customer".equals(actor.audience()))throw ApiError.forbidden("单位联系人仅向本单位客户管理员开放");
        String own=actor.user().tenantId();
        if(selection.empty())return new ExportData(CONTACT_COLUMNS,List.of());
        requireCustomerSelection(actor,tenant,selection);
        var rows=new ArrayList<List<String>>();
        for(int page=1;;page++){var result=customerNotifications.contacts(actor,page,100,"");for(var r:result.items())rows.add(Arrays.asList(Objects.toString(r.get("id"),""),Objects.toString(r.get("name"),""),Objects.toString(r.get("contact_type"),""),"",Objects.toString(r.get("enabled"),"false"),Objects.toString(r.get("revision"),"")));if(page*100>=result.total())break;}
        return new ExportData(CONTACT_COLUMNS,rows);
    }
    private void requireCustomerSelection(AuthService.Identity actor,String tenant,ScopeFilter.Selection selection) {
        if(!"customer".equals(actor.audience())||!"CUSTOMER".equals(actor.user().userType()))throw ApiError.forbidden("单位配置仅向本单位客户管理员开放");
        String own=actor.user().tenantId();
        if(own==null||(tenant!=null&&!tenant.equals(own))||selection.tenantIds().stream().anyMatch(id->!own.equals(id)))throw ApiError.forbidden("客户导出仅限当前单位范围");
        if(!selection.placeIds().isEmpty()) {
            var marks=String.join(",",Collections.nCopies(selection.placeIds().size(),"?"));
            var args=new ArrayList<Object>();args.add(own);args.addAll(selection.placeIds());
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE tenant_id=? AND id IN ("+marks+")",Integer.class,args.toArray());
            if(count!=selection.placeIds().size())throw ApiError.forbidden("场所不存在或不在当前授权范围内");
        }
    }
    private ExportData exportPolicies(AuthService.Identity actor,String tenant,ScopeFilter.Selection selection) {
        if(selection.empty())return new ExportData(POLICY_COLUMNS,List.of());
        var rows=new ArrayList<List<String>>();
        var units=scope.units(actor.user().id(),"t","id",tenant,selection);
        String unitSql="SELECT p.tenant_id,p.scope,p.scope_id,p.override_value,p.revision FROM parameter_overrides p JOIN tenants t ON t.id=p.tenant_id WHERE p.parameter_key='offline_threshold_minutes' AND p.scope='TENANT' AND p.override_value IS NOT NULL"+units.and()+" ORDER BY p.tenant_id,p.scope_id";
        for(var r:jdbc.queryForList(unitSql,units.bindings().toArray()))rows.add(Arrays.asList("TENANT:"+Objects.toString(r.get("scope_id"))+":offline_threshold_minutes","SET",Objects.toString(r.get("scope")),Objects.toString(r.get("scope_id")),"offline_threshold_minutes",Objects.toString(r.get("override_value")),Objects.toString(r.get("revision"))));
        var placeAccess=deviceAccess.placePredicate(actor.user().id(),"p.tenant_id","p.place_id");
        var placeTree=scope.devices(actor.user().id(),"p",tenant,selection);
        var placeArgs=new ArrayList<Object>();placeArgs.addAll(placeAccess.bindings());placeArgs.addAll(placeTree.bindings());
        String placeSql="SELECT p.scope,p.scope_id,p.override_value,p.revision FROM parameter_overrides p JOIN places loc ON loc.tenant_id=p.tenant_id AND loc.id=p.place_id WHERE p.parameter_key='offline_threshold_minutes' AND p.scope='PLACE' AND p.override_value IS NOT NULL AND loc.archived_at IS NULL"+placeAccess.and()+placeTree.and()+" ORDER BY p.tenant_id,p.scope_id";
        for(var r:jdbc.queryForList(placeSql,placeArgs.toArray()))rows.add(Arrays.asList("PLACE:"+Objects.toString(r.get("scope_id"))+":offline_threshold_minutes","SET",Objects.toString(r.get("scope")),Objects.toString(r.get("scope_id")),"offline_threshold_minutes",Objects.toString(r.get("override_value")),Objects.toString(r.get("revision"))));
        var access=deviceAccess.predicate(actor.user().id(),"d.tenant_id","d.id");
        var tree=scope.devices(actor.user().id(),"d",tenant,selection);
        var args=new ArrayList<Object>();args.addAll(access.bindings());args.addAll(tree.bindings());
        String sql="SELECT p.scope,p.scope_id,p.override_value,p.revision FROM parameter_overrides p JOIN devices d ON d.tenant_id=p.tenant_id AND d.id=p.scope_id WHERE p.parameter_key='offline_threshold_minutes' AND p.scope='DEVICE' AND p.override_value IS NOT NULL"+access.and()+tree.and()+" ORDER BY p.tenant_id,p.scope_id";
        for(var r:jdbc.queryForList(sql,args.toArray()))rows.add(Arrays.asList("DEVICE:"+Objects.toString(r.get("scope_id"))+":offline_threshold_minutes","SET",Objects.toString(r.get("scope")),Objects.toString(r.get("scope_id")),"offline_threshold_minutes",Objects.toString(r.get("override_value")),Objects.toString(r.get("revision"))));
        return new ExportData(POLICY_COLUMNS,rows);
    }

    private static String message(RuntimeException e){return e.getMessage()==null?"导入行无效":e.getMessage();}
    private static JsonNode json(Map<String,String> c,String field){try{String value=required(c,field);return new ObjectMapper().readTree(value);}catch(Exception e){throw ApiError.bad(field+"必须是有效JSON");}}
    private static String required(Map<String,String> c,String key){String v=c.get(key);if(v==null||v.isBlank())throw ApiError.bad(key+"不能为空");return v;}
    private static void text(Map<String,String> c,String key,int max){String v=required(c,key);if(v.length()>max)throw ApiError.bad(key+"超过长度限制");}
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static String blankNull(String s){return blank(s)?null:s;}
    private static long longValue(Map<String,String> c,String key){try{return Long.parseLong(required(c,key));}catch(NumberFormatException e){throw ApiError.bad(key+"必须是整数");}}
    private static Long nullableLong(String value){if(blank(value))return null;try{return Long.valueOf(value);}catch(NumberFormatException e){throw ApiError.bad("整数格式无效");}}
    private static String text(JsonNode n,String field){JsonNode v=n.get(field);if(v==null||!v.isTextual())throw ApiError.bad("receivers_json字段结构无效");return v.asText();}
    private static Long number(JsonNode n,String field){JsonNode v=n.get(field);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong())throw ApiError.bad("receivers_json字段结构无效");return v.longValue();}
}
