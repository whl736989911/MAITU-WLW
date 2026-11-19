package com.fireplatform.ingestion;

import com.fireplatform.shared.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageQueries {
    record StoredRaw(IngestionModels.RawSummary summary,byte[] payload) { }
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes; private final IdempotencyStore json; private final DeviceAccessScope scope; private final ScopeFilter scopes;
    public MessageQueries(JdbcTemplate jdbc,PageSizePolicy sizes,IdempotencyStore json,DeviceAccessScope scope,ScopeFilter scopes) { this.jdbc=jdbc; this.sizes=sizes; this.json=json; this.scope=scope; this.scopes=scopes; }
    private IngestionModels.RawSummary summary(ResultSet rs) throws SQLException { return new IngestionModels.RawSummary(rs.getString("id"),rs.getString("tenant_id"),rs.getString("source_id"),rs.getString("source_name"),rs.getString("source_message_id"),rs.getString("vendor_device_id"),rs.getString("device_id"),rs.getString("device_name"),rs.getString("binding_id"),rs.getString("product_version_id"),rs.getObject("received_at",OffsetDateTime.class),IngestionLimits.decode(rs.getBytes("reported_at_utf8")),rs.getLong("payload_bytes"),rs.getString("payload_sha256"),rs.getString("binding_state"),rs.getLong("duplicate_count"),rs.getLong("revision"),rs.getString("latest_preview_id"),rs.getString("parse_status"),rs.getString("business_status"),rs.getString("application_state"),rs.getObject("applied_at",OffsetDateTime.class),rs.getString("apply_error"),rs.getString("capture_provenance"),rs.getObject("occurred_at",OffsetDateTime.class),rs.getString("occurred_at_raw")); }
    StoredRaw raw(String actor,String tenant,String id,boolean lock) { var f=scope.predicate(actor,"raw_messages.tenant_id","raw_messages.device_id");var args=new ArrayList<Object>(List.of(tenant,id));args.addAll(f.bindings());var rows=jdbc.query("SELECT * FROM raw_messages WHERE tenant_id=? AND id=?"+f.and()+(lock?" FOR UPDATE":""),(rs,n)->new StoredRaw(summary(rs),rs.getBytes("payload_utf8")),args.toArray());if(rows.isEmpty())throw ApiError.missing();return rows.getFirst(); }
    StoredRaw rawSystem(String tenant,String id,boolean lock) { var rows=jdbc.query("SELECT * FROM raw_messages WHERE tenant_id=? AND id=?"+(lock ? " FOR UPDATE" : ""),(rs,n)->new StoredRaw(summary(rs),rs.getBytes("payload_utf8")),tenant,id); if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst(); }
    @Transactional(readOnly=true) public Page<IngestionModels.RawSummary> messages(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,String keyword,String source) {
        if(source!=null) {
            var sourceScope=scopes.units(actor,"ingestion_sources","tenant_id",tenant,selection);
            var sourceArgs=new ArrayList<Object>(); sourceArgs.add(source); sourceArgs.addAll(sourceScope.bindings());
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_sources WHERE id=? AND "+sourceScope.sql(),Long.class,sourceArgs.toArray())!=1)throw ApiError.missing();
        }
        var p=Pagination.of(page,size,sizes.defaultPageSize());String like=SourceQueries.like(keyword);
        var unitScope=scopes.units(actor,"raw_messages","tenant_id",tenant,selection);
        var f=scope.predicate(actor,"raw_messages.tenant_id","raw_messages.device_id");
        var allDevices=scope.allPredicate(actor,"raw_messages.tenant_id");
        var args=new ArrayList<Object>(unitScope.bindings());args.addAll(f.bindings());args.addAll(allDevices.bindings());
        String where=" FROM raw_messages WHERE "+unitScope.sql()+" AND ("+f.sql()+" OR (raw_messages.device_id IS NULL AND "+allDevices.sql()+"))";
        if(!selection.tenantIds().isEmpty()||!selection.placeIds().isEmpty()) {
            var selectedDevices=scopes.devices(actor,"d",tenant,selection);
            var selectedUnitNulls=new StringBuilder("raw_messages.device_id IS NULL AND ");
            if(selection.tenantIds().isEmpty()) selectedUnitNulls.append("1=0");
            else {
                selectedUnitNulls.append("raw_messages.tenant_id IN (").append(String.join(",",Collections.nCopies(selection.tenantIds().size(),"?"))).append(") AND ").append(allDevices.sql());
            }
            where+=" AND ("+selectedUnitNulls+" OR EXISTS (SELECT 1 FROM devices d WHERE d.tenant_id=raw_messages.tenant_id AND d.id=raw_messages.device_id AND "+selectedDevices.sql()+"))";
            if(!selection.tenantIds().isEmpty()) { args.addAll(selection.tenantIds()); args.addAll(allDevices.bindings()); }
            args.addAll(selectedDevices.bindings());
        }
        if(source!=null){where+=" AND source_id=?";args.add(source);}
        where+=" AND (LOWER(source_message_id) LIKE ? ESCAPE '!' OR LOWER(vendor_device_id) LIKE ? ESCAPE '!' OR LOWER(device_name) LIKE ? ESCAPE '!' OR LOWER(source_name) LIKE ? ESCAPE '!')"; for(int i=0;i<4;i++) args.add(like);
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray()); args.add(p.size()); args.add(p.offset()); var rows=jdbc.query("SELECT *"+where+" ORDER BY received_at DESC,id LIMIT ? OFFSET ?",(rs,n)->summary(rs),args.toArray()); return new Page<>(rows,total,p.page(),p.size());
    }
    private IngestionModels.Preview previewRow(ResultSet rs,boolean raw) throws SQLException {
        String storedReadings=rs.getString("readings_json");
        List<com.fireplatform.product.ProductModels.MeasurementReading> readings=storedReadings==null ? List.of() : List.of(json.parse(storedReadings,com.fireplatform.product.ProductModels.MeasurementReading[].class));
        return new IngestionModels.Preview(rs.getString("id"),rs.getString("raw_message_id"),rs.getString("product_version_id"),rs.getString("event_type"),rs.getBigDecimal("battery_percent"),readings,List.of(json.parse(rs.getString("diagnostics_json"),String[].class)),rs.getString("reported_at_iso")==null ? null : OffsetDateTime.parse(rs.getString("reported_at_iso")),rs.getString("time_quality"),raw ? json.parse(rs.getString("raw_event_json"),JsonNode.class) : null,rs.getObject("created_at",OffsetDateTime.class),rs.getString("actor_id"),rs.getString("reason"),rs.getString("business_status"));
    }
    public IngestionModels.Preview preview(String actor,String tenant,String rawId,String id,boolean includeRaw) { raw(actor,tenant,rawId,false);var rows=jdbc.query("SELECT * FROM message_previews WHERE tenant_id=? AND raw_message_id=? AND id=?",(rs,n)->previewRow(rs,includeRaw),tenant,rawId,id); if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst(); }
    @Transactional(readOnly=true) public IngestionModels.MessageDetail detail(String actor,String tenant,String id) { var message=raw(actor,tenant,id,false).summary(); var rows=jdbc.query("SELECT * FROM message_previews WHERE tenant_id=? AND raw_message_id=? ORDER BY created_at,id",(rs,n)->previewRow(rs,false),tenant,id); return new IngestionModels.MessageDetail(message,rows); }
    @Transactional(readOnly=true) public IngestionModels.RawContent content(String actor,String tenant,String id) { var row=raw(actor,tenant,id,false); return new IngestionModels.RawContent(id,IngestionLimits.decode(row.payload()),row.summary().payloadBytes(),row.summary().payloadSha256()); }
}
