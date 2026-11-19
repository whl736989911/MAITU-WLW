package com.fireplatform.notification;

import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class NotificationReads {
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes; private final DeviceAccessScope scope; private final ScopeFilter tree;
    private static final String RULE_FIELDS="SELECT r.id,r.tenant_id,r.name,r.event_scope,r.enabled,r.revision,"
        +"r.sms_escalation_interval_seconds,r.voice_escalation_interval_seconds,r.wechat_escalation_interval_seconds,"
        +"r.created_at,r.scope_type,r.scope_place_id,r.scope_device_id,u.name AS tenant_name";
    public NotificationReads(JdbcTemplate jdbc,PageSizePolicy sizes,DeviceAccessScope scope,ScopeFilter tree){this.jdbc=jdbc;this.sizes=sizes;this.scope=scope;this.tree=tree;}
    static Map<String,Object> pick(Map<String,Object> row,String... keys){var out=new LinkedHashMap<String,Object>();for(String key:keys){Object value=row.get(key);if(value instanceof java.sql.Timestamp stamp)value=stamp.toInstant().atOffset(java.time.ZoneOffset.UTC);out.put(key,value);}return out;}
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=ApiError.class)
    Map<String,Object> row(String table,String tenant,String id,boolean lock){String where=tenant==null?"id=?":"tenant_id=? AND id=?";Object[] args=tenant==null?new Object[]{id}:new Object[]{tenant,id};var found=jdbc.queryForList("SELECT * FROM "+table+" WHERE "+where+(lock?" FOR UPDATE":""),args);if(found.isEmpty())throw ApiError.missing();return found.getFirst();}
    static long revision(Map<String,Object> row){return ((Number)row.get("revision")).longValue();}
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static String string(Map<String,Object> row,String key){return (String)row.get(key);}
    static boolean enabled(Map<String,Object> row){return Boolean.TRUE.equals(row.get("enabled"));}
    private Page<Map<String,Object>> list(String select,String from,List<Object> args,int page,Integer size,String order,java.util.function.Function<Map<String,Object>,Map<String,Object>> project){var p=Pagination.of(page,size,sizes.defaultPageSize());long count=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());var a=new ArrayList<>(args);a.add(p.size());a.add(p.offset());var rows=jdbc.queryForList(select+from+" ORDER BY "+order+" LIMIT ? OFFSET ?",a.toArray()).stream().map(project).toList();return new Page<>(rows,count,p.page(),p.size());}
    static Map<String,Object> contact(Map<String,Object> row){return pick(row,"id","name","contact_type","phone_masked","wechat_binding_id","enabled","revision","created_at");}
    public Map<String,Object> contact(String tenant,String id){
        var rows=jdbc.queryForList("SELECT c.*,u.name AS tenant_name FROM notification_contacts c JOIN tenants u ON u.id=c.tenant_id WHERE c.tenant_id=? AND c.id=?",tenant,id);
        if(rows.isEmpty())throw ApiError.missing();
        var out=contact(rows.getFirst());out.put("tenant_id",tenant);out.put("tenant_name",rows.getFirst().get("tenant_name"));return out;
    }
    private static void keyword(StringBuilder where,List<Object> args,String keyword,String a,String b){String value=LiteralLike.normalizedKeyword(keyword);if(!value.isEmpty()){where.append(" AND (LOWER("+a+") LIKE LOWER(?) ESCAPE '!' OR LOWER("+b+") LIKE LOWER(?) ESCAPE '!')");String like=LiteralLike.contains(value);args.add(like);args.add(like);}}
    public Page<Map<String,Object>> contacts(String tenant,int page,Integer size,String keyword,Boolean enabled){var w=new StringBuilder(" FROM notification_contacts c JOIN tenants u ON u.id=c.tenant_id WHERE c.tenant_id=?");var args=new ArrayList<Object>();args.add(tenant);keyword(w,args,keyword,"c.id","c.name");if(enabled!=null){w.append(" AND c.enabled=?");args.add(enabled);}return list("SELECT c.*,u.name AS tenant_name",w.toString(),args,page,size,"c.created_at DESC,c.id",r->{var out=contact(r);out.put("tenant_id",r.get("tenant_id"));out.put("tenant_name",r.get("tenant_name"));return out;});}
    public Page<Map<String,Object>> contacts(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,String keyword,Boolean enabled){var f=tree.units(actor,"c","tenant_id",tenant,selection);var w=new StringBuilder(" FROM notification_contacts c JOIN tenants u ON u.id=c.tenant_id WHERE 1=1").append(f.and());var args=new ArrayList<Object>(f.bindings());keyword(w,args,keyword,"c.id","c.name");if(enabled!=null){w.append(" AND c.enabled=?");args.add(enabled);}return list("SELECT c.*,u.name AS tenant_name",w.toString(),args,page,size,"c.created_at DESC,c.id",r->{var out=contact(r);out.put("tenant_id",r.get("tenant_id"));out.put("tenant_name",r.get("tenant_name"));return out;});}
    public Page<Map<String,Object>> rules(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,
                                         String keyword,Boolean enabled,String scopeType,String scopeId){
        var f=tree.units(actor,"r","tenant_id",tenant,selection);
        var w=new StringBuilder(" FROM notification_rules r JOIN tenants u ON u.id=r.tenant_id WHERE r.legacy_mode IS NULL").append(f.and());
        var args=new ArrayList<Object>(f.bindings());
        appendRuleScopeAccess(actor,w,args);
        keyword(w,args,keyword,"r.id","r.name");
        if(enabled!=null){w.append(" AND r.enabled=?");args.add(enabled);}
        if(scopeType!=null||scopeId!=null){
            String type=NotificationPolicy.choice(scopeType,List.of("UNIT","PLACE","DEVICE"));
            w.append(" AND r.scope_type=? AND COALESCE(r.scope_device_id,r.scope_place_id,r.tenant_id)=?");
            args.add(type);args.add(NotificationPolicy.id(scopeId));
        }
        var rows=list(RULE_FIELDS,w.toString(),args,page,size,"r.created_at DESC,r.id",NotificationReads::realRule);
        return new Page<>(enrichRules(rows.items()),rows.total(),rows.page(),rows.size());
    }
    private void appendRuleScopeAccess(String actor,StringBuilder where,List<Object> args){
        var place=scope.allPredicate(actor,"r.tenant_id");
        var device=scope.predicate(actor,"r.tenant_id","r.scope_device_id");
        where.append(" AND (r.scope_type='UNIT' OR (r.scope_type='PLACE' AND ").append(place.sql())
            .append(") OR (r.scope_type='DEVICE' AND ").append(device.sql()).append("))");
        args.addAll(place.bindings());args.addAll(device.bindings());
    }
    public Map<String,Object> ruleForActor(String actor,String tenant,String id){
        var where=new StringBuilder(" FROM notification_rules r JOIN tenants u ON u.id=r.tenant_id WHERE r.tenant_id=? AND r.id=? AND r.legacy_mode IS NULL");
        var args=new ArrayList<Object>(List.of(tenant,id));
        appendRuleScopeAccess(actor,where,args);
        return firstRule(jdbc.queryForList(RULE_FIELDS+where,args.toArray()));
    }
    public Map<String,Object> rule(String tenant,String id){
        return firstRule(jdbc.queryForList(RULE_FIELDS+" FROM notification_rules r JOIN tenants u ON u.id=r.tenant_id WHERE r.tenant_id=? AND r.id=? AND r.legacy_mode IS NULL",tenant,id));
    }
    private Map<String,Object> firstRule(List<Map<String,Object>> rows){
        if(rows.isEmpty())throw ApiError.missing();
        return enrichRules(List.of(realRule(rows.getFirst()))).getFirst();
    }

    /** Candidates for the notification operation, not the actor's scope configuration or device records. */
    public Map<String,Object> ruleTargets(String actor,String tenant,String scopeType,int page,String search,String targetId){
        String type=NotificationPolicy.choice(scopeType,List.of("UNIT","PLACE","DEVICE"));
        if(targetId!=null) targetId=NotificationPolicy.id(targetId);
        var p=Pagination.of(page,null,20);
        boolean all=scope.all(actor,tenant);
        boolean device="DEVICE".equals(type);
        String activeDevices=" FROM devices d WHERE d.tenant_id=? AND d.archived_at IS NULL";
        List<Object> deviceArgs=List.of(tenant);
        if(!all){
            var filter=scope.predicate(actor,"d.tenant_id","d.id");
            activeDevices+=filter.and();
            deviceArgs=new ArrayList<>(deviceArgs);
            deviceArgs.addAll(filter.bindings());
        }

        long total=0;
        List<Map<String,Object>> items=List.of();
        if(device||("PLACE".equals(type)&&all)){
            var where=new StringBuilder(device?activeDevices:" FROM places d WHERE d.tenant_id=? AND d.archived_at IS NULL");
            var args=new ArrayList<>(device?deviceArgs:List.of(tenant));
            if(targetId!=null){ where.append(" AND d.id=?"); args.add(targetId); }
            keyword(where,args,search,"d.id","d.name");
            total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());
            if(total>p.offset()){
                args.add(p.size()); args.add(p.offset());
                items=jdbc.query("SELECT d.id,d.name"+where+" ORDER BY d.name,d.id LIMIT ? OFFSET ?",
                    (rs,n)->Map.<String,Object>of("id",rs.getString(1),"name",rs.getString(2)),args.toArray());
            }
        }
        // Keyword, page and exact-ID misses must not be mistaken for absence of any usable scope.
        boolean hasDevice=all||device&&total>0;
        if(!hasDevice&&(!device||targetId!=null||(search!=null&&!search.isBlank())))
            hasDevice=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1"+activeDevices+")",Boolean.class,deviceArgs.toArray()));
        return Map.of("tenant_id",tenant,"scope_type",type,"access",all?"ALL":hasDevice?"DEVICE_ONLY":"NONE",
            "items",items,"total",total,"page",p.page(),"size",p.size());
    }

    public Map<String,Object> effective(String actor,String tenant,String deviceId,String eventScope){
        String device=NotificationPolicy.id(deviceId),event=NotificationPolicy.choice(eventScope,List.of("FIRE","FAULT"));
        scope.requireDevice(actor,tenant,device);
        if(jdbc.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? AND archived_at IS NULL",String.class,tenant,device).isEmpty())
            throw ApiError.missing();
        var receivers=NotificationRecipients.resolve(jdbc,tenant,device,event).stream().map(r->Map.of(
            "contact_id",r.contactId(),"contact_name",r.contactName(),"phone_masked",r.phoneMasked(),"channel",r.channel(),
            "level",r.level(),"delay_seconds",r.delaySeconds(),"source_scope_type",r.scopeType(),"source_scope_id",r.scopeId(),
            "source_rule_id",r.ruleId(),"source_revision",r.revision())).toList();
        return Map.of("tenant_id",tenant,"device_id",device,"event_scope",event,"receivers",receivers);
    }
    private static Map<String,Object> realRule(Map<String,Object> row){
        var out=pick(row,"id","tenant_id","tenant_name","name","event_scope","enabled","revision","created_at");
        var target=NotificationRuleTarget.fromRule((String)row.get("tenant_id"),row);
        out.put("scope_type",target.type());out.put("scope_id",target.id());
        Number sms=(Number)row.get("sms_escalation_interval_seconds"),voice=(Number)row.get("voice_escalation_interval_seconds"),wechat=(Number)row.get("wechat_escalation_interval_seconds");
        out.put("sms_escalation_interval_minutes",sms==null?0L:sms.longValue()/60L);
        out.put("voice_escalation_interval_minutes",voice==null?0L:voice.longValue()/60L);
        out.put("wechat_escalation_interval_minutes",wechat==null?null:wechat.longValue()/60L);
        return out;
    }
    private List<Map<String,Object>> enrichRules(List<Map<String,Object>> rows){if(rows.isEmpty())return List.of();String slots=String.join(",",Collections.nCopies(rows.size(),"?"));Object[] ids=rows.stream().map(r->r.get("id")).toArray();var receivers=jdbc.queryForList("SELECT x.rule_id,x.contact_id,x.channel,x.level,c.name AS contact_name,c.phone_masked,c.wechat_binding_id,b.status AS wechat_binding_status,b.openid_masked AS wechat_openid_masked FROM notification_rule_receivers x JOIN notification_contacts c ON c.id=x.contact_id AND c.tenant_id=x.tenant_id LEFT JOIN notification_wechat_bindings b ON b.id=c.wechat_binding_id AND b.tenant_id=c.tenant_id WHERE x.rule_id IN ("+slots+") ORDER BY x.channel,x.level,x.contact_id",ids);var result=new ArrayList<Map<String,Object>>();for(var row:rows){var out=new LinkedHashMap<>(row);out.put("receivers",receivers.stream().filter(x->Objects.equals(x.get("rule_id"),row.get("id"))).map(x->pick(x,"channel","contact_id","level","contact_name","phone_masked","wechat_binding_id","wechat_binding_status","wechat_openid_masked")).toList());result.add(out);}return result;}
}
