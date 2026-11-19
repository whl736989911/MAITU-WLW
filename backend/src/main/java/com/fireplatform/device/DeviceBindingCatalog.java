package com.fireplatform.device;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only locked snapshot; ingestion must not query or write device tables itself. */
@Service
public class DeviceBindingCatalog {
    public record Device(String id,String name,String address,String productVersionId,boolean simulated) { }
    private final DeviceQueryService queries;private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public DeviceBindingCatalog(DeviceQueryService queries,org.springframework.jdbc.core.JdbcTemplate jdbc) { this.queries=queries;this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY) public Device lock(String actor,String tenant,String deviceId) {
        var row=queries.device(actor,tenant,deviceId,true); return new Device(row.id(),row.name(),row.address(),row.productVersionId(),row.isSimulated());
    }
    @Transactional(propagation=Propagation.MANDATORY) public Device lockSystem(String tenant,String deviceId) {
        var rows=jdbc.query("SELECT id,name,address,product_version_id,is_simulated FROM devices WHERE tenant_id=? AND id=? AND archived_at IS NULL FOR UPDATE",(r,n)->new Device(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBoolean(5)),tenant,deviceId);
        if(rows.isEmpty())throw com.fireplatform.shared.ApiError.missing();return rows.getFirst();
    }
}
