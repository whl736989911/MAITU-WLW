package com.fireplatform.alarm;

import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** DB-only publication. Its coordination lock is never acquired by business capture. */
@Service
public class AlarmChangePublisher {
    private record Pending(String id,String tenant) { }
    private record Head(String stream,long original,long next) { }
    private final JdbcTemplate jdbc;private final RuntimeConfig config;private final TransactionRequirements transactions;
    public AlarmChangePublisher(JdbcTemplate jdbc,RuntimeConfig config,TransactionRequirements transactions){this.jdbc=jdbc;this.config=config;this.transactions=transactions;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public int publish(){
        if(!config.alarmChangePublisherEnabled())return 0;
        transactions.readCommitted();
        jdbc.queryForObject("SELECT id FROM alarm_change_publisher_coord WHERE id=1 FOR UPDATE",Integer.class);
        var pending=jdbc.query("SELECT o.id,o.tenant_id FROM alarm_change_outbox o WHERE o.publication_sequence IS NULL AND NOT EXISTS (SELECT 1 FROM alarm_trace_entries t JOIN alarms a ON a.tenant_id=t.tenant_id AND a.id=t.alarm_id WHERE t.id=o.id AND t.tenant_id=o.tenant_id AND a.is_simulated=TRUE) ORDER BY o.recorded_at,o.id LIMIT ?",(rs,n)->new Pending(rs.getString(1),rs.getString(2)),config.alarmChangeBatchSize());
        var heads=new LinkedHashMap<String,Head>();
        for(var row:pending){
            Head head=heads.get(row.tenant());
            if(head==null){
                var stored=jdbc.query("SELECT stream_id,last_sequence FROM alarm_change_heads WHERE tenant_id=? FOR UPDATE",(rs,n)->new Head(rs.getString(1),rs.getLong(2),rs.getLong(2)),row.tenant());
                if(stored.isEmpty()){head=new Head(UUID.randomUUID().toString(),0,0);if(jdbc.update("INSERT INTO alarm_change_heads(tenant_id,stream_id,last_sequence) VALUES (?,?,0)",row.tenant(),head.stream())!=1)throw new IllegalStateException("Publication head was not created");}
                else head=stored.getFirst();
            }
            if(head.next()==Long.MAX_VALUE)throw ApiError.conflict("PUBLICATION_SEQUENCE_EXHAUSTED","发布位置已达上限");
            long next=head.next()+1;
            if(jdbc.update("UPDATE alarm_change_outbox SET publication_sequence=? WHERE id=? AND tenant_id=? AND publication_sequence IS NULL",next,row.id(),row.tenant())!=1)throw new IllegalStateException("Publication changed concurrently");
            heads.put(row.tenant(),new Head(head.stream(),head.original(),next));
        }
        for(var entry:heads.entrySet())if(jdbc.update("UPDATE alarm_change_heads SET last_sequence=? WHERE tenant_id=? AND last_sequence=? AND stream_id=?",entry.getValue().next(),entry.getKey(),entry.getValue().original(),entry.getValue().stream())!=1)throw new IllegalStateException("Publication head changed concurrently");
        return pending.size();
    }
}
