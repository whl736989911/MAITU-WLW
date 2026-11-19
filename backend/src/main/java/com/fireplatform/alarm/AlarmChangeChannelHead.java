package com.fireplatform.alarm;

import com.fireplatform.shared.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One feed-only snapshot; the proxy closes this transaction before fresh authorization and network I/O. */
@Service
public class AlarmChangeChannelHead {
    public record Head(String stream,long sequence) { }
    private final JdbcTemplate jdbc;private final com.fireplatform.shared.DeviceAccessScope scopes;
    public AlarmChangeChannelHead(JdbcTemplate jdbc,com.fireplatform.shared.DeviceAccessScope scopes){this.jdbc=jdbc;this.scopes=scopes;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Head read(String actor,String tenant){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("Head snapshot requires read-only transaction");
        var filter=scopes.predicate(actor,"o.tenant_id","o.device_id");
        var args=new java.util.ArrayList<Object>();args.add(tenant);args.addAll(filter.bindings());args.add(tenant);
        var head=jdbc.queryForObject("WITH publications AS (SELECT COUNT(*) AS source_count,COALESCE(MAX(publication_sequence),0) AS source_max FROM alarm_change_outbox WHERE tenant_id=? AND publication_sequence IS NOT NULL) "+
            "SELECT h.stream_id,COALESCE(h.last_sequence,0) AS source_head,publications.source_count,publications.source_max,"+
            "(SELECT COUNT(*) FROM alarm_change_outbox o WHERE o.tenant_id=target.tenant_id AND o.publication_sequence IS NOT NULL"+filter.and()+" AND NOT EXISTS (SELECT 1 FROM alarm_trace_entries t JOIN alarms a ON a.tenant_id=t.tenant_id AND a.id=t.alarm_id WHERE t.id=o.id AND t.tenant_id=o.tenant_id AND a.is_simulated=TRUE)) AS visible_head "+
            "FROM (SELECT CAST(? AS VARCHAR(64)) AS tenant_id) target CROSS JOIN publications LEFT JOIN alarm_change_heads h ON h.tenant_id=target.tenant_id",(rs,n)->{
                long sourceHead=rs.getLong("source_head");
                if(sourceHead!=rs.getLong("source_count")||sourceHead!=rs.getLong("source_max"))throw new ApiError(503,"CHANNEL_UNAVAILABLE","通道暂不可用");
                return new Head(rs.getString("stream_id"),rs.getLong("visible_head"));
            },args.toArray());
        if(head.sequence()<0||(head.stream()==null&&head.sequence()!=0)||(head.stream()!=null&&!head.stream().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))throw new ApiError(503,"CHANNEL_UNAVAILABLE","通道暂不可用");return head;
    }
}
