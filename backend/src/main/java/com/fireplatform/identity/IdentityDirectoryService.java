package com.fireplatform.identity;

import com.fireplatform.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityDirectoryService {
    public record AssignedUser(String id,String username,String name,String department,String tenant,boolean enabled) { }
    @Transactional(readOnly=true) public Page<AssignedUser> assignedUsers(String actorId,String roleId,int page,Integer size) {
        var p=Pagination.of(page,size,sizes.defaultPageSize());
        Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM access_roles WHERE id=? AND deleted=FALSE",Long.class,roleId);
        if(exists==null||exists!=1)throw ApiError.missing();
        String from=" FROM access_user_roles a JOIN app_users u ON u.id=a.user_id LEFT JOIN identity_user_metadata m ON m.user_id=u.id LEFT JOIN identity_departments d ON d.id=m.department_id LEFT JOIN tenants t ON t.id=u.tenant_id WHERE a.role_id=? AND (u.user_type='PLATFORM' OR EXISTS(SELECT 1 FROM user_tenant_grants g WHERE g.user_id=? AND g.tenant_id=u.tenant_id))";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,roleId,actorId);
        var rows=jdbc.query("SELECT u.id,u.username,u.display_name,COALESCE(d.name,'') department,COALESCE(t.name,'平台账号') tenant,u.enabled"+from+" ORDER BY u.tenant_id,u.id LIMIT ? OFFSET ?",(r,n)->new AssignedUser(r.getString("id"),r.getString("username"),r.getString("display_name"),r.getString("department"),r.getString("tenant"),r.getBoolean("enabled")),roleId,actorId,p.size(),p.offset());
        return new Page<>(rows,total,p.page(),p.size());
    }
    public record Department(String id,String tenantId,String parentId,String name,boolean enabled,long revision) { }
    public record UserProfile(String id,String username,String name,String tenantId,String tenantName,String departmentId,String departmentName,String phone,String contactEmail,OffsetDateTime validUntil,boolean longTerm,boolean enabled,long revision) { }
    private final JdbcTemplate jdbc; private final ManagementGuard guard; private final IdentityQueries identities; private final ManagementBounds bounds; private final SecretCommandStore commands; private final AuditService audit; private final PageSizePolicy sizes; private final ScopeFilter scopeFilter;
    public IdentityDirectoryService(JdbcTemplate jdbc,ManagementGuard guard,IdentityQueries identities,ManagementBounds bounds,SecretCommandStore commands,AuditService audit,PageSizePolicy sizes,ScopeFilter scopeFilter) { this.jdbc=jdbc; this.guard=guard; this.identities=identities; this.bounds=bounds; this.commands=commands; this.audit=audit; this.sizes=sizes; this.scopeFilter=scopeFilter; }
    private Department dept(ResultSet r) throws SQLException { return new Department(r.getString("id"),r.getString("tenant_id"),r.getString("parent_id"),r.getString("name"),r.getBoolean("enabled"),r.getLong("revision")); }
    private UserProfile profile(ResultSet r) throws SQLException { return new UserProfile(r.getString("id"),r.getString("username"),r.getString("display_name"),r.getString("tenant_id"),r.getString("tenant_name"),r.getString("department_id"),r.getString("department_name"),r.getString("phone"),r.getString("contact_email"),r.getObject("valid_until",OffsetDateTime.class),r.getBoolean("long_term"),r.getBoolean("enabled"),r.getLong("metadata_revision")); }
    @Transactional(readOnly=true) public List<Department> departments(String tenant) { return jdbc.query("SELECT id,tenant_id,parent_id,name,enabled,revision FROM identity_departments WHERE tenant_id=? ORDER BY parent_id,name,id",(r,n)->dept(r),tenant); }
    @Transactional public Department saveDepartment(AuthService.Identity actor,String tenant,String id,String key,IdentityDirectoryInputs.Department in,String requestId) {
        guard.begin(actor,"users:write",tenant,null);
        String op=id==null?"identity-department-create":"identity-department-update"; String hash=commands.hashBusiness(key,List.of(tenant,id==null?"":id,in.name().trim(),Objects.toString(in.parentId(),""),in.enabled(),in.expectedRevision()));
        Department replay=commands.replay(actor.user().id(),op,key,hash,null,Department.class); if(replay!=null)return replay;
        if(in.parentId()!=null) { Long parent=jdbc.queryForObject("SELECT COUNT(*) FROM identity_departments WHERE id=? AND tenant_id=? AND enabled=TRUE",Long.class,in.parentId(),tenant); if(parent==null||parent!=1) throw ApiError.bad("部门父节点无效或已停用"); }
        Department result;
        if(id==null) { String newId=UUID.randomUUID().toString(); jdbc.update("INSERT INTO identity_departments(id,tenant_id,parent_id,name,enabled,revision) VALUES (?,?,?,?,?,0)",newId,tenant,in.parentId(),in.name().trim(),in.enabled()); result=department(newId,tenant); }
        else { var rows=jdbc.queryForList("SELECT revision FROM identity_departments WHERE id=? AND tenant_id=? FOR UPDATE",Long.class,id,tenant); if(rows.isEmpty())throw ApiError.missing(); long rev=rows.getFirst(); if(rev!=in.expectedRevision())throw ApiError.conflict("REVISION_CONFLICT","部门已更新，请刷新"); if(id.equals(in.parentId())||isDescendant(id,in.parentId(),tenant))throw ApiError.bad("不能将部门移动到自身或其子部门"); jdbc.update("UPDATE identity_departments SET parent_id=?,name=?,enabled=?,revision=revision+1 WHERE id=?",in.parentId(),in.name().trim(),in.enabled(),id); result=department(id,tenant); }
        audit.record(actor.user().id(),tenant,op.toUpperCase(Locale.ROOT),result.id(),"SUCCESS",requestId); commands.save(actor.user().id(),op,key,hash,null,result); return result;
    }
    private boolean isDescendant(String root,String candidate,String tenant) { if(candidate==null)return false; return jdbc.queryForObject("WITH RECURSIVE d(id) AS (SELECT id FROM identity_departments WHERE id=? AND tenant_id=? UNION ALL SELECT x.id FROM identity_departments x JOIN d ON x.parent_id=d.id WHERE x.tenant_id=?) SELECT COUNT(*) FROM d WHERE id=?",Long.class,root,tenant,tenant,candidate)>0; }
    private Department department(String id,String tenant) { return jdbc.queryForObject("SELECT id,tenant_id,parent_id,name,enabled,revision FROM identity_departments WHERE id=? AND tenant_id=?",(r,n)->dept(r),id,tenant); }
    @Transactional(readOnly=true) public Page<UserProfile> users(AuthService.Identity actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,String keyword,String departmentId,Boolean enabledOnly) {
        var p=Pagination.of(page,size,sizes.defaultPageSize()); String like=IdentityQueries.keyword(keyword);
        String from=" FROM app_users u JOIN tenants t ON t.id=u.tenant_id LEFT JOIN identity_user_metadata m ON m.user_id=u.id LEFT JOIN identity_departments d ON d.id=m.department_id";
        ScopeFilter.Clause owner=scopeFilter.units(actor.user().id(),"u","tenant_id",tenant,selection);
        String where=" WHERE u.user_type='CUSTOMER' AND t.enabled=TRUE AND ("+owner.sql()+")";
        List<Object> args=new ArrayList<>(owner.bindings());
        where+=" AND (LOWER(u.username) LIKE ? ESCAPE '!' OR LOWER(u.display_name) LIKE ? ESCAPE '!' OR LOWER(COALESCE(m.phone,'')) LIKE ? ESCAPE '!')";args.add(like);args.add(like);args.add(like);
        if(departmentId!=null&&!departmentId.isBlank()){where+=" AND m.department_id=?";args.add(departmentId);}
        if(enabledOnly!=null){where+=" AND u.enabled=?";args.add(enabledOnly);}
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());
        args.add(p.size());args.add(p.offset());
        var rows=jdbc.query("SELECT u.id,u.username,u.display_name,u.tenant_id,t.name tenant_name,m.department_id,d.name department_name,COALESCE(m.phone,'') phone,COALESCE(m.contact_email,'') contact_email,m.valid_until,COALESCE(m.long_term,TRUE) long_term,u.enabled,COALESCE(m.revision,0) metadata_revision"+from+where+" ORDER BY u.tenant_id,u.id LIMIT ? OFFSET ?",(r,n)->profile(r),args.toArray());
        return new Page<>(rows,total,p.page(),p.size());
    }
    @Transactional(readOnly=true) private UserProfile userProfile(String id,String tenant) {
        var rows=jdbc.query("SELECT u.id,u.username,u.display_name,u.tenant_id,t.name tenant_name,m.department_id,d.name department_name,COALESCE(m.phone,'') phone,COALESCE(m.contact_email,'') contact_email,m.valid_until,COALESCE(m.long_term,TRUE) long_term,u.enabled,COALESCE(m.revision,0) metadata_revision FROM app_users u JOIN tenants t ON t.id=u.tenant_id LEFT JOIN identity_user_metadata m ON m.user_id=u.id LEFT JOIN identity_departments d ON d.id=m.department_id WHERE u.id=? AND u.tenant_id=? AND u.user_type='CUSTOMER'",(r,n)->profile(r),id,tenant);
        if(rows.isEmpty())throw ApiError.missing();
        return rows.getFirst();
    }
    @Transactional public UserProfile saveMetadata(AuthService.Identity actor,String tenant,String id,String key,IdentityDirectoryInputs.UserMetadata in,String requestId) {
        guard.begin(actor,"users:write",tenant,null); var target=identities.target(id,"CUSTOMER",tenant,true); bounds.protect(actor,target); bounds.check(actor,target);
        if(in.departmentId()!=null) { Long count=jdbc.queryForObject("SELECT COUNT(*) FROM identity_departments WHERE id=? AND tenant_id=? AND enabled=TRUE",Long.class,in.departmentId(),tenant); if(count==null||count!=1)throw ApiError.bad("部门无效或已停用"); }
        if(in.longTerm()==null)throw ApiError.bad("有效期必须明确"); if((in.longTerm()&&in.validUntil()!=null)||(!in.longTerm()&&in.validUntil()==null))throw ApiError.bad("长期有效必须无截止时间，非长期有效必须设置截止时间"); if(in.validUntil()!=null&&!in.validUntil().isAfter(OffsetDateTime.now().toInstant()))throw ApiError.bad("有效期必须晚于当前时间");
        String phone=Objects.requireNonNullElse(in.phone(),"").trim(),email=Objects.requireNonNullElse(in.contactEmail(),"").trim(); String hash=commands.hashBusiness(key,List.of(tenant,id,Objects.toString(in.departmentId(),""),phone,email,in.longTerm(),Objects.toString(in.validUntil(),""),in.expectedRevision())); String op="identity-user-metadata"; UserProfile replay=commands.replay(actor.user().id(),op,key,hash,null,UserProfile.class);if(replay!=null)return replay;
        var old=jdbc.queryForList("SELECT revision FROM identity_user_metadata WHERE user_id=? FOR UPDATE",Long.class,id);long rev=old.isEmpty()?0:old.getFirst();if(rev!=in.expectedRevision())throw ApiError.conflict("REVISION_CONFLICT","用户资料已更新，请刷新");
        if(old.isEmpty())jdbc.update("INSERT INTO identity_user_metadata(user_id,department_id,phone,contact_email,valid_until,long_term,revision) VALUES (?,?,?,?,?,?,1)",id,in.departmentId(),phone,email,in.longTerm()?null:OffsetDateTime.ofInstant(in.validUntil(),java.time.ZoneOffset.UTC),in.longTerm()); else jdbc.update("UPDATE identity_user_metadata SET department_id=?,phone=?,contact_email=?,valid_until=?,long_term=?,revision=revision+1 WHERE user_id=?",in.departmentId(),phone,email,in.longTerm()?null:OffsetDateTime.ofInstant(in.validUntil(),java.time.ZoneOffset.UTC),in.longTerm(),id);
        UserProfile result=userProfile(id,tenant);audit.record(actor.user().id(),tenant,"IDENTITY-USER-METADATA",id,"SUCCESS",requestId);commands.save(actor.user().id(),op,key,hash,null,result);return result;
    }
}
