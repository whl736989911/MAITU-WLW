package com.fireplatform.identity;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TenantService {
    public record TenantRow(String id,String name) { }
    private final JdbcTemplate jdbc;
    public TenantService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<TenantRow> authorizedTenants(String userId) {
        return jdbc.query("SELECT t.id,t.name FROM tenants t JOIN user_tenant_grants g ON g.tenant_id=t.id WHERE g.user_id=? AND t.enabled=TRUE ORDER BY t.id",(rs,n)->new TenantRow(rs.getString(1),rs.getString(2)),userId);
    }
}
