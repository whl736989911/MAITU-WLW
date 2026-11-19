package com.fireplatform.identity;

import com.fireplatform.shared.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityQueries {
    public record Unit(String id,String name,boolean enabled,long revision) { }
    public record ManagedUser(String id,String username,String name,String userType,String tenantId,String tenantName,boolean enabled,boolean mustChangePassword,long revision,boolean isProtected,List<String> permissions,List<String> tenantIds) { }
    private static final String USER_COLUMNS="id,username,display_name,user_type,tenant_id,(SELECT name FROM tenants t WHERE t.id=app_users.tenant_id) AS tenant_name,enabled,must_change_password,revision,is_protected";
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes; private final ScopeFilter scopeFilter;
    public IdentityQueries(JdbcTemplate jdbc,PageSizePolicy sizes,ScopeFilter scopeFilter) { this.jdbc=jdbc; this.sizes=sizes; this.scopeFilter=scopeFilter; }
    static String keyword(String keyword) {
        if(keyword==null) keyword=""; if(keyword.length()>Limits.MAX_KEYWORD_LENGTH) throw ApiError.bad("搜索关键字过长");
        return "%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    static Unit unitRow(ResultSet rs) throws SQLException { return new Unit(rs.getString("id"),rs.getString("name"),rs.getBoolean("enabled"),rs.getLong("revision")); }
    public Unit unit(String id) { return jdbc.queryForObject("SELECT id,name,enabled,revision FROM tenants WHERE id=?",(rs,n)->unitRow(rs),id); }
    @Transactional(readOnly=true) public Page<Unit> units(String actor,int page,Integer size,String keyword) {
        var p=Pagination.of(page,size,sizes.defaultPageSize()); String like=keyword(keyword);
        String from=" FROM tenants t JOIN user_tenant_grants g ON g.tenant_id=t.id WHERE g.user_id=? AND LOWER(t.name) LIKE ? ESCAPE '!'";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,actor,like);
        var rows=jdbc.query("SELECT t.id,t.name,t.enabled,t.revision"+from+" ORDER BY t.id LIMIT ? OFFSET ?",(rs,n)->unitRow(rs),actor,like,p.size(),p.offset());
        return new Page<>(rows,total,p.page(),p.size());
    }
    private ManagedUser base(ResultSet rs) throws SQLException {
        return new ManagedUser(rs.getString("id"),rs.getString("username"),rs.getString("display_name"),rs.getString("user_type"),rs.getString("tenant_id"),rs.getString("tenant_name"),rs.getBoolean("enabled"),rs.getBoolean("must_change_password"),rs.getLong("revision"),rs.getBoolean("is_protected"),List.of(),List.of());
    }
    private ManagedUser enrich(ManagedUser user) {
        var permissions=jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id=? ORDER BY permission",String.class,user.id());
        var grants=user.userType().equals("PLATFORM") ? jdbc.queryForList("SELECT tenant_id FROM user_tenant_grants WHERE user_id=? ORDER BY tenant_id",String.class,user.id()) : List.<String>of();
        return new ManagedUser(user.id(),user.username(),user.name(),user.userType(),user.tenantId(),user.tenantName(),user.enabled(),user.mustChangePassword(),user.revision(),user.isProtected(),permissions,grants);
    }
    ManagedUser target(String id,String type,String tenant,boolean lock) {
        String where=type.equals("CUSTOMER") ? "id=? AND user_type='CUSTOMER' AND tenant_id=?" : "id=? AND user_type='PLATFORM'";
        Object[] args=type.equals("CUSTOMER") ? new Object[]{id,tenant} : new Object[]{id};
        var rows=jdbc.query("SELECT "+USER_COLUMNS+" FROM app_users WHERE "+where+(lock ? " FOR UPDATE" : ""),(rs,n)->base(rs),args);
        if(rows.isEmpty()) throw ApiError.missing(); return enrich(rows.getFirst());
    }
    @Transactional(readOnly=true) public Page<ManagedUser> users(String type,String tenant,int page,Integer size,String keyword) {
        var p=Pagination.of(page,size,sizes.defaultPageSize()); String like=keyword(keyword);
        String where=type.equals("CUSTOMER") ? "user_type='CUSTOMER' AND tenant_id=? AND (LOWER(username) LIKE ? ESCAPE '!' OR LOWER(display_name) LIKE ? ESCAPE '!')" : "user_type='PLATFORM' AND (LOWER(username) LIKE ? ESCAPE '!' OR LOWER(display_name) LIKE ? ESCAPE '!')";
        Object[] args=type.equals("CUSTOMER") ? new Object[]{tenant,like,like} : new Object[]{like,like};
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE "+where,Long.class,args);
        var paged=new java.util.ArrayList<Object>(java.util.Arrays.asList(args)); paged.add(p.size()); paged.add(p.offset());
        var base=jdbc.query("SELECT "+USER_COLUMNS+" FROM app_users WHERE "+where+" ORDER BY id LIMIT ? OFFSET ?",(rs,n)->base(rs),paged.toArray());
        return new Page<>(base.stream().map(this::enrich).toList(),total,p.page(),p.size());
    }
    @Transactional(readOnly=true) public Page<ManagedUser> authorizedCustomers(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,String keyword) {
        var p=Pagination.of(page,size,sizes.defaultPageSize()); String like=keyword(keyword);
        var scope=scopeFilter.units(actor,"app_users","tenant_id",tenant,selection);
        String where=" WHERE app_users.user_type='CUSTOMER' AND "+scope.sql()+" AND (LOWER(app_users.username) LIKE ? ESCAPE '!' OR LOWER(app_users.display_name) LIKE ? ESCAPE '!')";
        var args=new ArrayList<Object>(scope.bindings()); args.add(like); args.add(like);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM app_users"+where,Long.class,args.toArray());
        args.add(p.size()); args.add(p.offset());
        var rows=jdbc.query("SELECT "+USER_COLUMNS+" FROM app_users"+where+" ORDER BY app_users.tenant_id,app_users.id LIMIT ? OFFSET ?",(rs,n)->base(rs),args.toArray());
        return new Page<>(rows.stream().map(this::enrich).toList(),total,p.page(),p.size());
    }
}
