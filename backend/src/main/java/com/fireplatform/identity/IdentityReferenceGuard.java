package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Conservative hard-delete fence: any non-current reference/history blocks erasure. */
@Component
public final class IdentityReferenceGuard {
    private record Reference(String table,String column,String schema) { }
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    public IdentityReferenceGuard(DataSource dataSource,JdbcTemplate jdbc) { this.dataSource=dataSource; this.jdbc=jdbc; }

    public void requireNoReferences(String parentTable,String id,Set<String> removableRelations,String message) {
        Set<Reference> refs=references(parentTable);
        for(Reference ref:refs) {
            if(removableRelations.contains(ref.table().toLowerCase(Locale.ROOT))) continue;
            String table=qualified(ref.schema(),ref.table());
            String column=quote(ref.column());
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?",Long.class,id);
            if(count!=null&&count>0) throw ApiError.conflict("IDENTITY_HAS_HISTORY",message+"（引用表："+ref.table()+"）");
        }
        if(parentTable.equalsIgnoreCase("tenants")) {
            if(count("audit_log","tenant_id",id)>0||count("audit_log","target_id",id)>0)
                throw ApiError.conflict("IDENTITY_HAS_HISTORY",message+"（审计历史）");
        } else if(parentTable.equalsIgnoreCase("app_users")) {
            if(count("audit_log","actor_id",id)>0||count("audit_log","target_id",id)>0)
                throw ApiError.conflict("IDENTITY_HAS_HISTORY",message+"（审计历史）");
        }
    }

    private long count(String table,String column,String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+quote(table)+" WHERE "+quote(column)+"=?",Long.class,id);
    }

    private Set<Reference> references(String parentTable) {
        var result=new LinkedHashSet<Reference>();
        try(var connection=dataSource.getConnection()) {
            DatabaseMetaData meta=connection.getMetaData();
            try(var tables=meta.getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})) {
                while(tables.next()) {
                    String actual=tables.getString("TABLE_NAME"),schema=tables.getString("TABLE_SCHEM");
                    if(!actual.equalsIgnoreCase(parentTable)) continue;
                    try(var keys=meta.getExportedKeys(connection.getCatalog(),schema,actual)) {
                        while(keys.next()) result.add(new Reference(keys.getString("FKTABLE_NAME"),keys.getString("FKCOLUMN_NAME"),keys.getString("FKTABLE_SCHEM")));
                    }
                }
            }
            return result;
        } catch(java.sql.SQLException error) { throw new IllegalStateException("Unable to inspect identity references",error); }
    }
    private String qualified(String schema,String table) { return schema==null||schema.isBlank()?quote(table):quote(schema)+"."+quote(table); }
    private String quote(String identifier) {
        if(identifier==null||!identifier.matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalStateException("Unexpected database identifier");
        try(var connection=dataSource.getConnection()) {
            String marker=connection.getMetaData().getIdentifierQuoteString();
            return marker==null||marker.isBlank()?identifier:marker+identifier+marker;
        } catch(java.sql.SQLException error) { throw new IllegalStateException("Unable to quote database identifier",error); }
    }
}
