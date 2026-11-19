package com.fireplatform.ingestion;

import com.fireplatform.shared.ApiError;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Read-only public fact/source fence. Does not expose any management HTTP raw-permission bypass. */
@Service
public class CapturedMessageCatalog {
    public record Fact(String id,String tenantId,String sourceId,String sourceMessageId,String vendorDeviceId,String deviceId,String deviceName,String deviceAddress,String bindingId,String productVersionId,OffsetDateTime receivedAt,String bindingState,String payload) { }
    public record SourceState(boolean enabled,boolean simulated,long revision,boolean bindingCurrent) { }
    private final JdbcTemplate jdbc;
    public CapturedMessageCatalog(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Fact read(String tenant,String raw) {
        var rows=jdbc.query("SELECT * FROM raw_messages WHERE tenant_id=? AND id=?",(rs,n)->new Fact(rs.getString("id"),tenant,rs.getString("source_id"),rs.getString("source_message_id"),rs.getString("vendor_device_id"),rs.getString("device_id"),rs.getString("device_name"),rs.getString("device_address"),rs.getString("binding_id"),rs.getString("product_version_id"),rs.getObject("received_at",OffsetDateTime.class),rs.getString("binding_state"),IngestionLimits.decode(rs.getBytes("payload_utf8"))),tenant,raw);
        if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst();
    }
    public SourceState source(Fact fact,boolean lock) {
        var rows=jdbc.query("SELECT enabled,is_simulated,revision FROM ingestion_sources WHERE id=? AND tenant_id=?"+(lock ? " FOR UPDATE" : ""),(rs,n)->new long[]{rs.getBoolean(1)?1:0,rs.getBoolean(2)?1:0,rs.getLong(3)},fact.sourceId(),fact.tenantId());
        if(rows.isEmpty()) throw ApiError.conflict("SOURCE_CHANGED","来源已变化"); var row=rows.getFirst();
        boolean binding=fact.bindingId()!=null && jdbc.queryForObject("SELECT COUNT(*) FROM active_source_bindings WHERE binding_id=? AND source_id=? AND device_id=? AND vendor_device_id=?",Long.class,fact.bindingId(),fact.sourceId(),fact.deviceId(),fact.vendorDeviceId())==1;
        return new SourceState(row[0]==1,row[1]==1,row[2],binding);
    }
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=ApiError.class) public SourceState lockSource(Fact fact) { return source(fact,true); }
}
