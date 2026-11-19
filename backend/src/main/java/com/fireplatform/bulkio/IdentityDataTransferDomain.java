package com.fireplatform.bulkio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.identity.*;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Limits;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Identity transfers are thin adapters over the existing guarded identity commands. */
@Component
public class IdentityDataTransferDomain implements DataTransferDomain {
    private static final List<String> USER_COLUMNS = List.of("id","username","name","enabled","permissions","permission_effect","expected_revision","reason","department_id","phone","contact_email","long_term","valid_until","metadata_revision","roles","role_effect","scope_tenant","scope_mode","device_ids","place_ids","scope_effect","scope_revision","scope_records");
    private static final List<String> PLATFORM_COLUMNS = List.of("id","username","name","enabled","permissions","permission_effect","expected_revision","reason","roles","role_effect","tenant_grants","grant_effect","scope_tenant","scope_mode","device_ids","place_ids","scope_effect","scope_revision","scope_records");
    private static final List<String> ROLE_COLUMNS = List.of("id","code","name","user_type","enabled","permissions","expected_revision","reason");
    private static final Set<String> CUSTOMER_AUDIENCES = Set.of("customer","management");
    private static final Set<String> MANAGEMENT = Set.of("management");
    private static final Set<String> SCOPE_MODES = Set.of("ALL_TENANT","DEVICE_SET","PLACE_SUBTREE");
    private static final SecureRandom RANDOM = new SecureRandom();
    private record ScopeTransfer(String tenant, String mode, List<String> deviceIds, List<String> placeIds, String effect, long revision) { }
    private final JdbcTemplate jdbc;
    private final AccountManagementService accounts;
    private final RoleAdminService roles;
    private final UserAccessService access;
    private final IdentityDirectoryService directory;
    private final AccessQueries accessQueries;
    private final ManagementBounds bounds;
    private final com.fireplatform.shared.ScopeFilter scopeFilter;
    private final ObjectMapper json;

    public IdentityDataTransferDomain(JdbcTemplate jdbc, AccountManagementService accounts, RoleAdminService roles,
            UserAccessService access, IdentityDirectoryService directory, AccessQueries accessQueries,
            ManagementBounds bounds, com.fireplatform.shared.ScopeFilter scopeFilter, ObjectMapper json) {
        this.jdbc=jdbc; this.accounts=accounts; this.roles=roles; this.access=access; this.directory=directory;
        this.accessQueries=accessQueries; this.bounds=bounds; this.scopeFilter=scopeFilter; this.json=json;
    }

    @Override public List<Kind> kinds() { return List.of(
        new Kind("USERS",USER_COLUMNS,"users:read","users:write",true,true,true,CUSTOMER_AUDIENCES,"MASTER_DATA"),
        new Kind("PLATFORM_USERS",PLATFORM_COLUMNS,"platform-users:read","platform-users:write",true,true,false,MANAGEMENT,"MASTER_DATA"),
        new Kind("ROLES",ROLE_COLUMNS,"roles:read","roles:write",true,true,false,MANAGEMENT,"MASTER_DATA")); }

    @Override public List<String> validate(String kind, Map<String,String> c, AuthService.Identity actor, String tenant) {
        Kind descriptor=descriptor(kind); if(!descriptor.importable())throw ApiError.bad("该类型不支持导入");
        var errors=new ArrayList<String>();
        for(String key:c.keySet()) if(!descriptor.columns().contains(key))errors.add("不支持字段："+key);
            if(!actor.permissions().contains(kind.equals("ROLES")?"roles:write":kind.equals("USERS")?"users:write":"platform-users:write"))
                throw ApiError.forbidden("缺少身份导入所需权限");
            if(relationSupplied(c,"roles","role_effect")&&!actor.permissions().contains(kind.equals("PLATFORM_USERS")?"platform-users:assign-roles":"users:assign-roles"))
                throw ApiError.forbidden("缺少账号角色分配权限");
            if((present(c,"scope_effect")||present(c,"scope_records"))&&!actor.permissions().contains(kind.equals("PLATFORM_USERS")?"platform-users:assign-scope":"users:assign-scope"))
                throw ApiError.forbidden("缺少账号设备范围管理权限");
        try {
            if(kind.equals("ROLES")) validateRole(c);
            else validateUser(kind,c,tenant);
        } catch(RuntimeException e) { errors.add(Objects.toString(e.getMessage(),"字段无效")); }
        return List.copyOf(errors);
    }
    private void validateUser(String kind,Map<String,String> c,String tenant) {
        if(kind.equals("USERS")&&(present(c,"tenant_grants")||present(c,"grant_effect")))throw ApiError.bad("客户账号不能修改平台单位授权");
        if(kind.equals("PLATFORM_USERS")&&hasMetadata(c))throw ApiError.bad("平台账号不支持客户资料元数据");
        boolean create=blank(c.get("id"));
        if(create) {
            if(!c.containsKey("username")||!c.containsKey("name")||!c.containsKey("permissions"))throw ApiError.bad("新账号必须提供username、name和permissions；初始密码由系统安全生成，账号保持停用");
            if(blank(c.get("name"))||c.get("name").length()>200)throw ApiError.bad("name不能为空且不能超过200字符");
            String u=c.get("username"); if(u==null||!u.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{2,99}"))throw ApiError.bad("username格式无效");
            if(kind.equals("PLATFORM_USERS")&&!c.containsKey("tenant_grants"))throw ApiError.bad("新平台账号必须显式提供tenant_grants及grant_effect=REPLACE");
            if(kind.equals("PLATFORM_USERS")&& !"REPLACE".equals(c.get("grant_effect")))throw ApiError.bad("新平台账号tenant_grants_effect必须为REPLACE");
        } else {
            number(c,"expected_revision");
            if(!present(c,"name")&&!present(c,"enabled")&&!hasRelation(c)&&!hasMetadata(c))throw ApiError.bad("账号行没有可更新字段");
        }
        if(present(c,"enabled")) bool(c,"enabled");
        if(present(c,"permissions")||present(c,"permission_effect")) {
            if(present(c,"permissions"))validateList(parse(c.get("permissions")),Limits.MAX_PERMISSION_ENTRIES,64,"permissions");
            effect(c,"permission_effect",true);
        }
        if(kind.equals("PLATFORM_USERS")&&present(c,"tenant_grants")) {
            effect(c,"grant_effect",true);
            validateList(parse(c.get("tenant_grants")),Limits.MAX_DELEGATED_GRANTS,64,"tenant_grants");
        }
        if(relationSupplied(c,"roles","role_effect")) {
            if(present(c,"roles"))validateList(parse(c.get("roles")),Limits.MAX_ROLE_ASSIGNMENTS,64,"roles");
            effect(c,"role_effect",true);
        }
        if(present(c,"scope_effect")) {
            String effect=c.get("scope_effect"); if(!Set.of("ADD","REMOVE","REPLACE").contains(effect))throw ApiError.bad("scope_effect必须为ADD、REMOVE或REPLACE");
            if(blank(c.get("scope_tenant"))||blank(c.get("scope_mode"))||!SCOPE_MODES.contains(c.get("scope_mode")))throw ApiError.bad("设备范围必须显式指定scope_tenant与scope_mode");
            if(!c.containsKey("device_ids")||!c.containsKey("place_ids"))throw ApiError.bad("设备范围必须同时显式提供device_ids与place_ids");
            validateList(parse(c.get("device_ids")),Limits.MAX_SCOPE_DEVICES,64,"device_ids");
            validateList(parse(c.get("place_ids")),Limits.MAX_SCOPE_PLACES,64,"place_ids");
            List<String> devices=parse(c.get("device_ids")),places=parse(c.get("place_ids"));
            number(c,"scope_revision");
            switch(c.get("scope_mode")) {
                case "ALL_TENANT" -> {
                    if(!effect.equals("REPLACE"))throw ApiError.bad("ALL_TENANT范围只支持REPLACE");
                    if(!devices.isEmpty()||!places.isEmpty())throw ApiError.bad("ALL_TENANT必须使用空device_ids与place_ids");
                }
                case "DEVICE_SET" -> {
                    if(!places.isEmpty())throw ApiError.bad("DEVICE_SET必须使用空place_ids");
                }
                case "PLACE_SUBTREE" -> {
                    if(!effect.equals("REPLACE"))throw ApiError.bad("PLACE_SUBTREE范围只支持REPLACE");
                    if(!devices.isEmpty())throw ApiError.bad("PLACE_SUBTREE必须使用空device_ids");
                    if(places.isEmpty())throw ApiError.bad("PLACE_SUBTREE必须指定至少一个位置根");
                }
                default -> throw ApiError.bad("设备范围模式无效");
            }
        }
        if(present(c,"scope_records")) {
            var scopeTenants=new HashSet<String>();
            for(var entry:parseScopes(c.get("scope_records"))) {
                if(blank(entry.tenant())||entry.tenant().length()>64||!scopeTenants.add(entry.tenant())||entry.mode()==null||!SCOPE_MODES.contains(entry.mode())||entry.effect()==null||!Set.of("ADD","REMOVE","REPLACE").contains(entry.effect())||entry.revision()<0||entry.revision()>AccessInputs.MAX_REVISION)
                    throw ApiError.bad("scope_records包含无效或重复设备范围");
                if(entry.deviceIds()==null||entry.placeIds()==null)throw ApiError.bad("scope_records必须显式提供deviceIds与placeIds");
                validateList(entry.deviceIds(),Limits.MAX_SCOPE_DEVICES,64,"scope_records.deviceIds");
                validateList(entry.placeIds(),Limits.MAX_SCOPE_PLACES,64,"scope_records.placeIds");
                boolean replace=entry.effect().equals("REPLACE");
                if(entry.mode().equals("ALL_TENANT")) {
                    if(!replace)throw ApiError.bad("ALL_TENANT范围只支持REPLACE");
                    if(!entry.deviceIds().isEmpty()||!entry.placeIds().isEmpty())throw ApiError.bad("ALL_TENANT范围必须没有deviceIds与placeIds");
                } else if(entry.mode().equals("DEVICE_SET")) {
                    if(!entry.placeIds().isEmpty())throw ApiError.bad("DEVICE_SET范围必须没有placeIds");
                } else {
                    if(!replace)throw ApiError.bad("PLACE_SUBTREE范围只支持REPLACE");
                    if(!entry.deviceIds().isEmpty()||entry.placeIds().isEmpty())throw ApiError.bad("PLACE_SUBTREE范围必须只有非空placeIds");
                }
            }
        }
        if(hasMetadata(c)) {
            if(!Set.of("department_id","phone","contact_email","long_term","valid_until","metadata_revision").stream().allMatch(c::containsKey))throw ApiError.bad("用户资料更新必须显式提供完整资料字段及metadata_revision");
            bool(c,"long_term"); number(c,"metadata_revision");
            if(!blank(c.get("department_id"))&&c.get("department_id").length()>64)throw ApiError.bad("department_id长度无效");
            if(c.getOrDefault("phone","").length()>18||c.getOrDefault("contact_email","").length()>254)throw ApiError.bad("phone或contact_email长度无效");
            if(!Boolean.parseBoolean(c.get("long_term"))&&blank(c.get("valid_until")))throw ApiError.bad("非长期有效账号必须设置valid_until");
            if(Boolean.parseBoolean(c.get("long_term"))&&!blank(c.get("valid_until")))throw ApiError.bad("长期有效账号的valid_until必须为空");
            if(!blank(c.get("valid_until")))OffsetDateTime.parse(c.get("valid_until"));
        }
        if(kind.equals("USERS")&&tenant==null)throw ApiError.bad("客户账号导入必须固定目标单位");
    }
    private void validateRole(Map<String,String> c) {
        if(blank(c.get("id"))) {
            if(!c.containsKey("code")||!c.get("code").matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw ApiError.bad("角色code格式无效");
            required(c,"name"); if(c.get("name").length()>200)throw ApiError.bad("角色name长度无效");
            required(c,"user_type"); bool(c,"enabled");
            validateList(parse(c.get("permissions")),Limits.MAX_PERMISSION_ENTRIES,64,"permissions");
        } else {
            number(c,"expected_revision"); required(c,"name"); if(c.get("name").length()>200)throw ApiError.bad("角色name长度无效");
            bool(c,"enabled");
            validateList(parse(c.get("permissions")),Limits.MAX_PERMISSION_ENTRIES,64,"permissions");
        }
        if(!blank(c.get("id"))) {
            var current=accessQueries.role(c.get("id"));
            if(present(c,"code")&&!c.get("code").equals(current.code()))throw ApiError.bad("角色code不可修改");
            if(present(c,"user_type")&&!c.get("user_type").equals(current.userType()))throw ApiError.bad("角色user_type不可修改");
        }
        if(!blank(c.get("user_type"))&&!Set.of("CUSTOMER","PLATFORM").contains(c.get("user_type")))throw ApiError.bad("user_type无效");
        if(!blank(c.get("reason")))AccessInputs.reason(c.get("reason"));
    }
    @Override public String operation(String kind,Map<String,String> cells) {
        if(kind.equals("ROLES"))return blank(cells.get("id"))?"创建全局角色模板":"更新全局角色模板（CAS）";
        return blank(cells.get("id"))?"创建停用账号（安全随机密码不返回；须由管理员重置密码后启用）":"更新账号；未提供的角色、权限、单位授权、设备范围和资料均保持不变";
    }
    @Override public String apply(String kind,Map<String,String> c,AuthService.Identity actor,String tenant,String reason,String jobId,int row) {
        var errors=validate(kind,c,actor,tenant);if(!errors.isEmpty())throw ApiError.bad(String.join("；",errors));
        String key="data-transfer:"+jobId+":"+row; String why=val(c,"reason",reason);
        if(kind.equals("ROLES"))return applyRole(c,actor,key,why);
        String type=kind.equals("USERS")?"CUSTOMER":"PLATFORM"; String id=c.get("id");
        if(blank(id)) {
            String temporary=randomPassword();
            List<String> permissions=relation(parse(c.get("permissions")),List.of(),val(c,"permission_effect","REPLACE"));
            IdentityQueries.ManagedUser created=type.equals("CUSTOMER")
                ? accounts.createCustomer(actor,tenant,key,new ManagementInputs.CreateCustomer(c.get("username"),required(c,"name"),temporary,permissions),key)
                : accounts.createPlatform(actor,key,new ManagementInputs.CreatePlatform(c.get("username"),required(c,"name"),temporary,permissions,parse(c.get("tenant_grants"))),key);
            id=created.id();
            // Keep the create and disable in the row transaction: the account is never observable enabled.
            if(created.enabled()) { var latest=load(id,type,tenant);
                if(type.equals("CUSTOMER")) accounts.updateCustomer(actor,tenant,id,subkey(key,"disable"),new ManagementInputs.UpdateCustomer(created.name(),false,created.permissions(),latest.revision()),key);
                else accounts.updatePlatform(actor,id,subkey(key,"disable"),new ManagementInputs.UpdatePlatform(created.name(),false,created.permissions(),created.tenantIds(),latest.revision()),key);
            }
        } else {
            var current=load(id,type,tenant);
            List<String> perms=present(c,"permissions")?relation(parse(c.get("permissions")),current.permissions(),c.get("permission_effect")):current.permissions();
            // Existing account update command validates delegation and atomically preserves grants passed in.
            boolean updateBase=present(c,"name")||present(c,"enabled")||present(c,"permissions")||(type.equals("PLATFORM")&&present(c,"tenant_grants"));
            if(updateBase) {
                boolean enabled=present(c,"enabled")?Boolean.parseBoolean(c.get("enabled")):current.enabled();
                List<String> grants=current.tenantIds();
                if(type.equals("PLATFORM")&&present(c,"tenant_grants"))grants=relation(parse(c.get("tenant_grants")),grants,c.get("grant_effect"));
                String name=present(c,"name")?c.get("name"):current.name();
                long expectedRevision=number(c,"expected_revision");
                if(type.equals("CUSTOMER"))accounts.updateCustomer(actor,tenant,id,key,new ManagementInputs.UpdateCustomer(name,enabled,perms,expectedRevision),key);
                else accounts.updatePlatform(actor,id,key,new ManagementInputs.UpdatePlatform(name,enabled,perms,grants,expectedRevision),key);
            }
        }
        applyMetadata(c,actor,tenant,type,id,key,why);
        applyRoles(c,actor,type,tenant,id,key,why);
        applyScopes(c,actor,type,tenant,id,key,why);
        return id;
    }
    private String applyRole(Map<String,String> c,AuthService.Identity actor,String key,String reason) {
        if(blank(c.get("id")))return roles.create(actor,key,new AccessInputs.CreateRole(c.get("code"),c.get("name"),c.get("user_type"),Boolean.valueOf(c.get("enabled")),parse(c.get("permissions")),reason),key).id();
        AccessModels.Role current=accessQueries.role(c.get("id"));
        return roles.update(actor,current.id(),key,new AccessInputs.UpdateRole(c.get("name"),Boolean.valueOf(c.get("enabled")),parse(c.get("permissions")),number(c,"expected_revision"),reason),key).id();
    }
    private void applyMetadata(Map<String,String> c,AuthService.Identity actor,String tenant,String type,String id,String key,String reason) {
        if(!hasMetadata(c))return;
        if(!type.equals("CUSTOMER"))throw ApiError.bad("平台账号不支持客户资料元数据");
        var in=new IdentityDirectoryInputs.UserMetadata(blank(c.get("department_id"))?null:c.get("department_id"),c.get("phone"),c.get("contact_email"),Boolean.valueOf(c.get("long_term")),blank(c.get("valid_until"))?null:OffsetDateTime.parse(c.get("valid_until")).toInstant(),number(c,"metadata_revision"));
        directory.saveMetadata(actor,tenant,id,subkey(key,"metadata"),in,key);
    }
    private void applyRoles(Map<String,String> c,AuthService.Identity actor,String type,String tenant,String id,String key,String reason) {
        if(!relationSupplied(c,"roles","role_effect"))return;
        var current=access.readAccess(type,tenant,id); var selected=relation(parse(c.get("roles")),current.roles().stream().map(AccessModels.Role::id).toList(),c.get("role_effect"));
        boolean updatesBase=present(c,"name")||present(c,"enabled")||present(c,"permissions")||(type.equals("PLATFORM")&&present(c,"tenant_grants"));
        long revision=updatesBase?load(id,type,tenant).revision():number(c,"expected_revision");
        access.assignRoles(actor,type,tenant,id,subkey(key,"roles"),new AccessInputs.AssignRoles(selected,revision,reason),key);
    }
    private void applyScope(Map<String,String> c,AuthService.Identity actor,String type,String tenant,String id,String key,String reason) {
        if(!present(c,"scope_effect"))return;
        String scopeTenant=c.get("scope_tenant"); if(type.equals("CUSTOMER")&&!scopeTenant.equals(tenant))throw ApiError.forbidden("设备范围单位必须为当前单位");
        var old=access.readScope(actor,type,scopeTenant,id); var devices=parse(c.getOrDefault("device_ids","")); var places=parse(c.getOrDefault("place_ids",""));
        if(c.get("scope_effect").equals("ADD")||c.get("scope_effect").equals("REMOVE")) {
            if(!old.configured()||!old.mode().equals("DEVICE_SET")||c.get("scope_mode").equals("PLACE_SUBTREE"))throw ApiError.bad("未配置、ALL_TENANT或PLACE_SUBTREE设备范围只支持REPLACE");
            devices=relation(devices,old.deviceIds(),c.get("scope_effect"));
        }
        long revision=number(c,"scope_revision");
        access.assignScope(actor,type,scopeTenant,id,subkey(key,"scope"),new AccessInputs.AssignScope(c.get("scope_mode"),devices,places,revision,reason),key);
    }
    private void applyScopes(Map<String,String> c,AuthService.Identity actor,String type,String tenant,String id,String key,String reason) {
        if(present(c,"scope_records")) {
            int index=0;
            for(var item:parseScopes(c.get("scope_records"))) {
                if(type.equals("CUSTOMER")&&!item.tenant().equals(tenant))throw ApiError.forbidden("设备范围单位必须为当前单位");
                var old=access.readScope(actor,type,item.tenant(),id);
                List<String> devices=item.deviceIds(),places=item.placeIds();
                if(item.effect().equals("ADD")||item.effect().equals("REMOVE")) {
                    if(!old.configured()||!old.mode().equals("DEVICE_SET")||item.mode().equals("PLACE_SUBTREE"))throw ApiError.bad("未配置、ALL_TENANT或PLACE_SUBTREE设备范围只支持REPLACE");
                    devices=relation(devices,old.deviceIds(),item.effect());
                }
                access.assignScope(actor,type,item.tenant(),id,subkey(key,"scope-"+index++),new AccessInputs.AssignScope(item.mode(),devices,places,item.revision(),reason),key);
            }
            return;
        }
        applyScope(c,actor,type,tenant,id,key,reason);
    }
    private IdentityQueries.ManagedUser load(String id,String type,String tenant) {
        String sql="SELECT u.id,u.username,u.display_name,u.user_type,u.tenant_id,t.name tenant_name,u.enabled,u.must_change_password,u.revision,u.is_protected FROM app_users u LEFT JOIN tenants t ON t.id=u.tenant_id WHERE u.id=? AND u.user_type=?"+(type.equals("CUSTOMER")?" AND u.tenant_id=?":"");
        Object[] args=type.equals("CUSTOMER")?new Object[]{id,type,tenant}:new Object[]{id,type};
        var found=jdbc.query(sql,(rs,n)->new IdentityQueries.ManagedUser(rs.getString("id"),rs.getString("username"),rs.getString("display_name"),rs.getString("user_type"),rs.getString("tenant_id"),rs.getString("tenant_name"),rs.getBoolean("enabled"),rs.getBoolean("must_change_password"),rs.getLong("revision"),rs.getBoolean("is_protected"),jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id=? ORDER BY permission",String.class,rs.getString("id")),jdbc.queryForList("SELECT tenant_id FROM user_tenant_grants WHERE user_id=? ORDER BY tenant_id",String.class,rs.getString("id"))),args);
        if(found.isEmpty())throw ApiError.missing();
        return found.getFirst();
    }
    @Override public ExportData export(String kind,AuthService.Identity actor,String tenant,Map<String,String> filters,List<String> selectedIds) {
        Kind descriptor=descriptor(kind); if(!descriptor.exportable())throw ApiError.bad("该类型不支持导出");
        var selection=DataTransferFilters.selection(filters,Set.of("user_type","enabled","keyword"));
        if(filters!=null&&present(filters,"enabled"))bool(filters,"enabled");
        List<List<String>> rows=new ArrayList<>(); List<String> columns=kind.equals("ROLES")?ROLE_COLUMNS:kind.equals("USERS")?USER_COLUMNS:PLATFORM_COLUMNS;
        if(kind.equals("ROLES")) {
            if(selection.empty()||!selection.tenantIds().isEmpty()||!selection.placeIds().isEmpty())throw ApiError.bad("全局角色导出不支持单位或位置筛选");
            String type=filters==null||!present(filters,"user_type")?null:filters.get("user_type"); if(type!=null&&!Set.of("CUSTOMER","PLATFORM").contains(type))throw ApiError.bad("user_type筛选无效");
            String keyword=keyword(filters);
            String sql="SELECT id,code,name,user_type,enabled,deleted,revision FROM access_roles WHERE (? IS NULL OR user_type=?) AND deleted=FALSE AND (? IS NULL OR LOWER(code) LIKE ? ESCAPE '!' OR LOWER(name) LIKE ? ESCAPE '!') ORDER BY id";
            String like=keyword==null?null:"%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            for(var r:jdbc.query(sql,(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5),rs.getBoolean(6),rs.getLong(7)},type,type,keyword,like,like)) {
                if(filters!=null&&present(filters,"enabled")&&((boolean)r[4])!=Boolean.parseBoolean(filters.get("enabled")))continue;
                String id=(String)r[0]; rows.add(List.of(id,(String)r[1],(String)r[2],(String)r[3],Boolean.toString((boolean)r[4]),String.join(",",jdbc.queryForList("SELECT permission FROM access_role_permissions WHERE role_id=? ORDER BY permission",String.class,id)),Long.toString((long)r[6]),""));
            }
        } else {
            if(filters!=null&&present(filters,"user_type"))throw ApiError.bad("该用户类型不支持user_type筛选");
            String type=kind.equals("USERS")?"CUSTOMER":"PLATFORM";
            String keyword=keyword(filters);
            String like=keyword==null?null:"%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            String sql;
            Object[] bindings;
            if(type.equals("CUSTOMER")) {
                var clause=scopeFilter.units(actor.user().id(),"u","tenant_id",tenant,selection);
                sql="SELECT u.id FROM app_users u WHERE u.user_type='CUSTOMER'"+clause.and()+" AND (? IS NULL OR LOWER(u.username) LIKE ? ESCAPE '!' OR LOWER(u.display_name) LIKE ? ESCAPE '!') ORDER BY u.id";
                var args=new ArrayList<Object>(clause.bindings()); args.add(keyword); args.add(like); args.add(like); bindings=args.toArray();
            } else {
                if(!selection.placeIds().isEmpty())throw ApiError.bad("平台账号导出不支持place_ids筛选");
                sql="SELECT id FROM app_users WHERE user_type='PLATFORM'"+(selection.empty()?" AND 1=0":"")+" AND (? IS NULL OR LOWER(username) LIKE ? ESCAPE '!' OR LOWER(display_name) LIKE ? ESCAPE '!') ORDER BY id";
                bindings=new Object[]{keyword,like,like};
            }
            List<String> ids=jdbc.queryForList(sql,String.class,bindings);
            for(String id:ids) {
                String entityTenant=type.equals("CUSTOMER")&&tenant==null
                    ?jdbc.queryForObject("SELECT tenant_id FROM app_users WHERE id=? AND user_type='CUSTOMER'",String.class,id):tenant;
                var u=load(id,type,entityTenant);
                if(type.equals("PLATFORM")&&!selection.tenantIds().isEmpty()&&Collections.disjoint(u.tenantIds(),selection.tenantIds()))continue;
                if(type.equals("PLATFORM")&&selection.empty())continue;
                try { bounds.check(actor,u); } catch(ApiError denied) { if(denied.status==403)continue; throw denied; }
                if(filters!=null&&present(filters,"enabled")&&u.enabled()!=Boolean.parseBoolean(filters.get("enabled")))continue;
                var meta=type.equals("CUSTOMER")?jdbc.query("SELECT department_id,phone,contact_email,long_term,valid_until,revision FROM identity_user_metadata WHERE user_id=?",rs->rs.next()?new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getObject(5,OffsetDateTime.class),rs.getLong(6)}:null,id):null;
                String rolesText=String.join(",",access.readAccess(type,entityTenant,id).roles().stream().map(AccessModels.Role::id).toList());
                String grants=String.join(",",u.tenantIds());
                var row=new ArrayList<String>(List.of(u.id(),u.username(),u.name(),Boolean.toString(u.enabled()),String.join(",",u.permissions()),"REPLACE",Long.toString(u.revision()),""));
                if(type.equals("CUSTOMER")) {
                    row.add(Objects.toString(meta==null?null:meta[0],"")); row.add(Objects.toString(meta==null?null:meta[1],""));
                    row.add(Objects.toString(meta==null?null:meta[2],"")); row.add(meta==null?"true":Boolean.toString((boolean)meta[3]));
                    row.add(meta==null||meta[4]==null?"":meta[4].toString()); row.add(meta==null?"0":Long.toString((long)meta[5]));
                    row.add(rolesText); row.add("REPLACE");
                } else {
                    row.add(rolesText); row.add("REPLACE"); row.add(grants); row.add("REPLACE");
                }
                row.add(""); row.add(""); row.add(""); row.add(""); row.add(""); row.add(""); row.add(scopeRecords(id));
                rows.add(row);
        }
        }
        return DataTransferFilters.selected(new ExportData(columns,rows),selectedIds);
    }

    private String scopeRecords(String id) {
        var stored=jdbc.query("SELECT tenant_id,mode,revision FROM user_device_scopes WHERE user_id=? ORDER BY tenant_id",
            (rs,n)->new ScopeTransfer(rs.getString("tenant_id"),rs.getString("mode"),List.of(),List.of(),"REPLACE",rs.getLong("revision")),id);
        var entries=stored.stream().map(scope->new ScopeTransfer(scope.tenant(),scope.mode(),
            scope.mode().equals("DEVICE_SET")?jdbc.queryForList("SELECT device_id FROM user_device_scope_devices WHERE user_id=? AND tenant_id=? ORDER BY device_id",String.class,id,scope.tenant()):List.of(),
            scope.mode().equals("PLACE_SUBTREE")?jdbc.queryForList("SELECT place_id FROM user_device_scope_places WHERE user_id=? AND tenant_id=? ORDER BY place_id",String.class,id,scope.tenant()):List.of(),
            scope.effect(),scope.revision())).toList();
        try { return json.writeValueAsString(entries); } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Scope export serialization failed",e); }
    }
    private Kind descriptor(String kind) { return kinds().stream().filter(k->k.kind().equals(kind)).findFirst().orElseThrow(()->ApiError.bad("身份导入导出类型无效")); }
    private List<ScopeTransfer> parseScopes(String value) {
        if(blank(value))return List.of();
        try { return json.readValue(value,new TypeReference<List<ScopeTransfer>>(){}); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw ApiError.bad("scope_records必须为有效范围JSON数组"); }
    }
    private static String keyword(Map<String,String> filters) {
        if(filters==null||!present(filters,"keyword"))return null;
        String value=filters.get("keyword");
        if(value.length()>Limits.MAX_KEYWORD_LENGTH)throw ApiError.bad("搜索关键字过长");
        return value;
    }
    private static String subkey(String key,String suffix){return "data-transfer-child:"+UUID.nameUUIDFromBytes((key+":"+suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static boolean relationSupplied(Map<String,String> c,String values,String effect){return present(c,effect)||present(c,values);}
    private static boolean hasMetadata(Map<String,String> c){return Set.of("department_id","phone","contact_email","long_term","valid_until","metadata_revision").stream().anyMatch(k->present(c,k));}
    private static boolean hasRelation(Map<String,String> c){return present(c,"permission_effect")||relationSupplied(c,"roles","role_effect")||present(c,"grant_effect")||present(c,"scope_effect")||present(c,"scope_records");}
    private static void validateList(List<String> values,int maximum,int maxLength,String field) {
        if(values==null||values.size()>maximum)throw ApiError.bad(field+"数量超过上限");
        var seen=new HashSet<String>();
        for(String value:values)if(blank(value)||value.length()>maxLength||!seen.add(value)||value.chars().anyMatch(Character::isISOControl))
            throw ApiError.bad(field+"包含无效或重复值");
    }
    private static String randomPassword(){byte[] b=new byte[48];RANDOM.nextBytes(b);return "Onboard-"+Base64.getUrlEncoder().withoutPadding().encodeToString(b)+"9aA!";}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static boolean present(Map<String,String> cells,String key){return cells.containsKey(key)&&!blank(cells.get(key));}
    private static String required(Map<String,String> c,String k){String v=c.get(k);if(blank(v))throw ApiError.bad(k+"不能为空");return v;}
    private static String val(Map<String,String> c,String k,String fallback){return present(c,k)?c.get(k):fallback;}
    private static void bool(Map<String,String> c,String k){if(!Set.of("true","false").contains(c.get(k)))throw ApiError.bad(k+"必须为true或false");}
    private static long number(Map<String,String> c,String k){try{long n=Long.parseLong(required(c,k));if(n<0||n>AccessInputs.MAX_REVISION)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw ApiError.bad(k+"版本号无效");}}
    private static List<String> csv(String v){if(v==null||v.isEmpty())return List.of();var out=new ArrayList<String>();for(String x:v.split(",",-1)){if(x.isBlank()||!x.equals(x.trim())||x.length()>256||!out.add(x)||x.chars().anyMatch(Character::isISOControl))throw ApiError.bad("逗号分隔字段含无效或重复值");}return List.copyOf(out);}
    private static List<String> parse(String v){return csv(v==null?"":v);}
    private static void effect(Map<String,String> c,String key,boolean required){String e=c.get(key);if(e==null&&!required)return;if(!Set.of("ADD","REMOVE","REPLACE").contains(e))throw ApiError.bad(key+"必须为ADD、REMOVE或REPLACE");}
    private static List<String> relation(List<String> input,List<String> current,String effect){if(effect==null||effect.equals("REPLACE"))return input;var values=new LinkedHashSet<>(current);if(effect.equals("ADD"))values.addAll(input);else if(effect.equals("REMOVE"))values.removeAll(input);else throw ApiError.bad("关系操作必须指定ADD、REMOVE或REPLACE");return values.stream().sorted().toList();}
}
