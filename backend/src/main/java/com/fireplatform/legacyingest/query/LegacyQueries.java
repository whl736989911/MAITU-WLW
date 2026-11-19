package com.fireplatform.legacyingest.query;

import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ScopeFilter;
import com.fireplatform.shared.Pagination;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegacyQueries {
    public record EventRow(String id, String kind, String method, String paramsJson, OffsetDateTime observedAt) { }
    public record MessageRow(String id, String ingress, String vendorPlatform, String vendorDeviceId, String deviceId,
                             OffsetDateTime receivedAt, OffsetDateTime observedAt, String state, String failureCode,
                             long duplicateCount, int eventCount, String tenantId) { }
    public record MessageDetail(MessageRow summary, String payloadRaw, boolean payloadRedacted, List<EventRow> events) { }
    public record ReconcileRow(String id, String runId, String kind, String legacyId, String newId, String detail, OffsetDateTime createdAt) { }
    public record DeviceRow(String id, String name, String deviceEui, String model, String tenantId, String placeName,
                            String legacyProductId, String physicalState, OffsetDateTime lastMessageAt) { }
    public record ChannelRow(String platform, String ingress, long messages, long normalized, long pendingDecoder,
                             long failed, long unknownDevice, long duplicates, OffsetDateTime lastReceivedAt) { }
    /** Authorization predicate reused by list reads and exports so both apply the same current scope. */
    public record QueryFilter(String fromWhere, List<Object> bindings) { }
    public record UnitRow(String id, String name) { }
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes; private final DeviceAccessScope scopes; private final ScopeFilter tree;
    public LegacyQueries(JdbcTemplate jdbc, PageSizePolicy sizes, DeviceAccessScope scopes, ScopeFilter tree) { this.jdbc=jdbc; this.sizes=sizes; this.scopes=scopes; this.tree=tree; }

    /** Explicit export/import targets: only units the actor currently holds and that are enabled. */
    @Transactional(readOnly = true) public List<UnitRow> units(String actor) {
        return jdbc.query("SELECT t.id,t.name FROM tenants t JOIN user_tenant_grants g ON g.tenant_id=t.id WHERE g.user_id=? AND t.enabled=TRUE ORDER BY t.name,t.id",
            (rs,n)->new UnitRow(rs.getString(1),rs.getString(2)),actor);
    }

    /** 每个实时通道最近的消息计数，用于确认“所有通道都在进数据”。 */
    @Transactional(readOnly = true) public List<ChannelRow> channels(String actor, String tenant, ScopeFilter.Selection selection) {
        var units=tree.units(actor,"legacy_ingest_messages","tenant_id",tenant,selection);
        var access=scopes.predicate(actor,"legacy_ingest_messages.tenant_id","legacy_ingest_messages.device_id");
        var allDevices=scopes.allPredicate(actor,"legacy_ingest_messages.tenant_id");
        var args=new ArrayList<Object>(units.bindings()); args.addAll(access.bindings()); args.addAll(allDevices.bindings());
        String where=" WHERE "+units.sql()+" AND ("+access.sql()+" OR (legacy_ingest_messages.device_id IS NULL AND "+allDevices.sql()+"))";
        if(!selection.tenantIds().isEmpty()||!selection.placeIds().isEmpty()) {
            var deviceSelection=tree.devices(actor,"d",tenant,selection);
            String nullRows="1=0";
            if(!selection.tenantIds().isEmpty()) {
                nullRows="(legacy_ingest_messages.device_id IS NULL AND legacy_ingest_messages.tenant_id IN ("+String.join(",",java.util.Collections.nCopies(selection.tenantIds().size(),"?"))+") AND "+allDevices.sql()+")";
                args.addAll(selection.tenantIds()); args.addAll(allDevices.bindings());
            }
            where+=" AND ("+nullRows+" OR EXISTS (SELECT 1 FROM devices d WHERE d.tenant_id=legacy_ingest_messages.tenant_id AND d.id=legacy_ingest_messages.device_id AND "+deviceSelection.sql()+"))";
            args.addAll(deviceSelection.bindings());
        }
        return jdbc.query("SELECT vendor_platform,ingress,COUNT(*),"+
            "COALESCE(SUM(CASE WHEN state='NORMALIZED' THEN 1 ELSE 0 END),0),"+
            "COALESCE(SUM(CASE WHEN state='PENDING_DECODER' THEN 1 ELSE 0 END),0),"+
            "COALESCE(SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END),0),"+
            "COALESCE(SUM(CASE WHEN failure_code='UNKNOWN_DEVICE' THEN 1 ELSE 0 END),0),"+
            "COALESCE(SUM(duplicate_count),0),MAX(received_at) "+
            "FROM legacy_ingest_messages"+where+
            " GROUP BY vendor_platform,ingress ORDER BY MAX(received_at) DESC",
            (rs,n)->new ChannelRow(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getObject(9,OffsetDateTime.class)),args.toArray());
    }

    /** Same predicate as the message list; exports must never widen it. */
    public QueryFilter messageFilter(String actor, String tenant, ScopeFilter.Selection selection) {
        var unit=tree.units(actor,"legacy_ingest_messages","tenant_id",tenant,selection);
        var access=scopes.predicate(actor,"legacy_ingest_messages.tenant_id","legacy_ingest_messages.device_id");
        var allDevices=scopes.allPredicate(actor,"legacy_ingest_messages.tenant_id");
        var args=new ArrayList<Object>(unit.bindings()); args.addAll(access.bindings()); args.addAll(allDevices.bindings());
        var where=new StringBuilder(" FROM legacy_ingest_messages WHERE ").append(unit.sql()).append(" AND (").append(access.sql()).append(" OR (legacy_ingest_messages.device_id IS NULL AND ").append(allDevices.sql()).append("))");
        if(!selection.tenantIds().isEmpty()||!selection.placeIds().isEmpty()) {
            var deviceSelection=tree.devices(actor,"d",tenant,selection);
            String nullRows="1=0";
            if(!selection.tenantIds().isEmpty()) {
                nullRows="(legacy_ingest_messages.device_id IS NULL AND legacy_ingest_messages.tenant_id IN ("+String.join(",",java.util.Collections.nCopies(selection.tenantIds().size(),"?"))+") AND "+allDevices.sql()+")";
                args.addAll(selection.tenantIds()); args.addAll(allDevices.bindings());
            }
            where.append(" AND (").append(nullRows).append(" OR EXISTS (SELECT 1 FROM devices d WHERE d.tenant_id=legacy_ingest_messages.tenant_id AND d.id=legacy_ingest_messages.device_id AND ").append(deviceSelection.sql()).append("))");
            args.addAll(deviceSelection.bindings());
        }
        return new QueryFilter(where.toString(),List.copyOf(args));
    }

    /** Same predicate as the reconcile list; a difference is visible only through its resolvable device. */
    public QueryFilter differenceFilter(String actor, String tenant, ScopeFilter.Selection selection) {
        var args=new ArrayList<Object>();
        var selected=tree.devices(actor,"d",tenant,selection);
        var access=scopes.predicate(actor,"d.tenant_id","d.id");
        var where=new StringBuilder(" FROM legacy_reconcile_differences WHERE 1=1");
        where.append(" AND EXISTS (SELECT 1 FROM devices d WHERE d.id=legacy_reconcile_differences.new_id AND ").append(selected.sql()).append(access.and()).append(")");
        args.addAll(selected.bindings());args.addAll(access.bindings());
        return new QueryFilter(where.toString(),List.copyOf(args));
    }

    @Transactional(readOnly = true) public Page<MessageRow> messages(String actor, String tenant, ScopeFilter.Selection selection, int page, Integer size, String state, String platform, String keyword) {
        var p=Pagination.of(page,size,sizes.defaultPageSize());
        var filter=messageFilter(actor,tenant,selection);
        var args=new ArrayList<Object>(filter.bindings());
        var where=new StringBuilder(filter.fromWhere());
        if(state!=null && !state.isBlank()) { where.append(" AND state=?"); args.add(state); }
        if(platform!=null && !platform.isBlank()) { where.append(" AND vendor_platform=?"); args.add(platform); }
        if(keyword!=null && !keyword.isBlank()) { where.append(" AND (LOWER(vendor_device_id) LIKE ? ESCAPE '!' OR LOWER(external_key) LIKE ? ESCAPE '!')"); String like=like(keyword); args.add(like); args.add(like); }
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where, Long.class, args.toArray());
        args.add(p.size()); args.add(p.offset());
        var rows=jdbc.query("SELECT id,ingress,vendor_platform,vendor_device_id,device_id,received_at,observed_at,state,failure_code,duplicate_count"+
            ",(SELECT COUNT(*) FROM legacy_ingest_events e WHERE e.message_id=legacy_ingest_messages.id),tenant_id"+where+" ORDER BY received_at DESC,id LIMIT ? OFFSET ?",
            (rs,n)->new MessageRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class),rs.getString(8),rs.getString(9),rs.getLong(10),rs.getInt(11),rs.getString(12)), args.toArray());
        return new Page<>(rows,total,p.page(),p.size());
    }


    @Transactional(readOnly = true) public MessageDetail detail(String actor, String tenant, String id, boolean includeRaw) {
        var f=scopes.predicate(actor,"legacy_ingest_messages.tenant_id","legacy_ingest_messages.device_id");
        var args=new ArrayList<Object>(List.of(tenant,id)); args.addAll(f.bindings());
        var rows=jdbc.query("SELECT id,ingress,vendor_platform,vendor_device_id,device_id,received_at,observed_at,state,failure_code,duplicate_count,payload_raw,tenant_id"+
            " FROM legacy_ingest_messages WHERE tenant_id=? AND id=?"+f.and(),(rs,n)->new Object[]{
            new MessageRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getObject(6,OffsetDateTime.class),rs.getObject(7,OffsetDateTime.class),rs.getString(8),rs.getString(9),rs.getLong(10),0,rs.getString(12)),rs.getString(11)}, args.toArray());
        if(rows.isEmpty()) throw com.fireplatform.shared.ApiError.missing();
        var events=jdbc.query("SELECT id,kind,method,params_json,observed_at FROM legacy_ingest_events WHERE message_id=? ORDER BY created_at,id",
            (rs,n)->new EventRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getObject(5,OffsetDateTime.class)), id);
        var summary=(MessageRow)rows.getFirst()[0];
        return new MessageDetail(new MessageRow(summary.id(),summary.ingress(),summary.vendorPlatform(),summary.vendorDeviceId(),summary.deviceId(),
            summary.receivedAt(),summary.observedAt(),summary.state(),summary.failureCode(),summary.duplicateCount(),events.size(),summary.tenantId()),
            includeRaw?(String)rows.getFirst()[1]:null,!includeRaw,events);
    }

    @Transactional(readOnly = true) public Page<ReconcileRow> differences(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,String runId) {
        var p=Pagination.of(page,size,sizes.defaultPageSize());
        var filter=differenceFilter(actor,tenant,selection);
        var args=new ArrayList<Object>(filter.bindings());
        var where=new StringBuilder(filter.fromWhere());
        if(runId!=null && !runId.isBlank()) { where.append(" AND run_id=?"); args.add(runId); }
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where, Long.class, args.toArray());
        args.add(p.size()); args.add(p.offset());
        var rows=jdbc.query("SELECT id,run_id,kind,legacy_id,new_id,detail,created_at"+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            (rs,n)->new ReconcileRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,OffsetDateTime.class)), args.toArray());
        return new Page<>(rows,total,p.page(),p.size());
    }

    @Transactional(readOnly = true) public Page<DeviceRow> devices(String actor, String tenant, ScopeFilter.Selection selection, int page, Integer size, String keyword) {
        var p=Pagination.of(page,size,sizes.defaultPageSize());
        var f=scopes.predicate(actor,"d.tenant_id","d.id");
        var selected=tree.devices(actor,"d",tenant,selection);
        var args=new ArrayList<Object>(selected.bindings()); args.addAll(f.bindings());
        var where=new StringBuilder(" FROM devices d LEFT JOIN places p ON p.id=d.place_id AND p.tenant_id=d.tenant_id"+
            " LEFT JOIN product_versions v ON v.id=d.product_version_id WHERE ").append(selected.sql()).append(f.and());
        if(keyword!=null && !keyword.isBlank()) { where.append(" AND (LOWER(d.name) LIKE ? ESCAPE '!' OR LOWER(d.device_eui) LIKE ? ESCAPE '!' OR LOWER(d.model) LIKE ? ESCAPE '!')"); String like=like(keyword); args.add(like); args.add(like); args.add(like); }
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where, Long.class, args.toArray());
        args.add(p.size()); args.add(p.offset());
        var rows=jdbc.query("SELECT d.id,d.name,d.device_eui,d.model,d.tenant_id,p.name,v.legacy_product_id,d.physical_state,d.last_message_at"+where+
            " ORDER BY d.name,d.id LIMIT ? OFFSET ?",(rs,n)->new DeviceRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
            rs.getString(6),rs.getString(7),rs.getString(8),rs.getObject(9,OffsetDateTime.class)), args.toArray());
        return new Page<>(rows,total,p.page(),p.size());
    }

    private static String like(String keyword) { return "%"+keyword.toLowerCase().replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
}
