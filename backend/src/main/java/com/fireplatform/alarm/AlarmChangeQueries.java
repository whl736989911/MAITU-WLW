package com.fireplatform.alarm;

import com.fireplatform.configuration.*;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AlarmChangeQueries {
    public record Scope(String tenantId,String audience,String streamId) { }
    public record Item(String deliveryId,String sequence,String type) { }
    public record Semantics(String kind,String order,String publisherLiveness,String baseline,String historicalCoverage) { }
    public record Result(String mode,OffsetDateTime asOf,String timezone,Scope scope,int size,List<Item> items,String nextCursor,String throughCursor,boolean hasMore,String pendingCount,Semantics semantics) { }
    private record State(String stream,long head,long pending,long sourceHead,long sourceCount,long sourceMax) { }
    private static final Semantics SEMANTICS=new Semantics("INVALIDATION_ONLY","PER_ACTOR_VISIBLE_PUBLICATION_RANK","UNCONFIRMED","HEAD_BEFORE_SEPARATE_WORKBENCH_READ","APPLICATION_TRACES_FROM_V11");
    private final JdbcTemplate jdbc;private final PageSizePolicy sizes;private final DatabaseTime time;private final TransactionRequirements transactions;private final RuntimeConfig config;private final DeviceAccessScope scopes;
    public AlarmChangeQueries(JdbcTemplate jdbc,PageSizePolicy sizes,DatabaseTime time,TransactionRequirements transactions,RuntimeConfig config,DeviceAccessScope scopes){this.jdbc=jdbc;this.sizes=sizes;this.time=time;this.transactions=transactions;this.config=config;this.scopes=scopes;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result read(AuthService.Identity identity,String tenant,String cursor,String through,Integer size){
        transactions.repeatableReadOnly();
        if(!config.alarmChangeReadEnabled())throw new ApiError(503,"ALARM_CHANGES_DISABLED","本机未启用报警变更读取");
        if(cursor==null&&through!=null)throw ApiError.bad("through必须同时提供cursor");
        String session=AlarmChangeCursor.session(identity.tokenHash());
        var supplied=cursor==null?null:AlarmChangeCursor.decode(cursor);var target=through==null?null:AlarmChangeCursor.decode(through);
        if(supplied!=null)requireScope(supplied,tenant,identity.audience(),session);if(target!=null)requireScope(target,tenant,identity.audience(),session);
        var pagination=Pagination.of(1,size,sizes.defaultPageSize());
        // Cursor ranks are dense over real publications visible to the actor in every scope.
        var asOf=time.now().withOffsetSameInstant(ZoneOffset.UTC);var visible=scopes.predicate(identity.user().id(),"pub.tenant_id","pub.device_id");var pendingScope=scopes.predicate(identity.user().id(),"p.tenant_id","p.device_id");
        String realChange=" AND NOT EXISTS (SELECT 1 FROM alarm_trace_entries t JOIN alarms a ON a.tenant_id=t.tenant_id AND a.id=t.alarm_id WHERE t.id=pub.id AND t.tenant_id=pub.tenant_id AND a.is_simulated=TRUE)";
        String realPending=" AND NOT EXISTS (SELECT 1 FROM alarm_trace_entries t JOIN alarms a ON a.tenant_id=t.tenant_id AND a.id=t.alarm_id WHERE t.id=p.id AND t.tenant_id=p.tenant_id AND a.is_simulated=TRUE)";
        var stateArgs=new ArrayList<Object>();stateArgs.add(tenant);stateArgs.addAll(visible.bindings());stateArgs.addAll(pendingScope.bindings());stateArgs.add(tenant);
        var state=jdbc.queryForObject("WITH publications AS (SELECT COUNT(*) AS source_count,COALESCE(MAX(publication_sequence),0) AS source_max FROM alarm_change_outbox WHERE tenant_id=? AND publication_sequence IS NOT NULL) "+
            "SELECT h.stream_id,COALESCE(h.last_sequence,0) AS source_head,publications.source_count,publications.source_max,"+
            "(SELECT COUNT(*) FROM alarm_change_outbox pub WHERE pub.tenant_id=target.tenant_id AND pub.publication_sequence IS NOT NULL"+visible.and()+realChange+") AS published_head,"+
            "(SELECT COUNT(*) FROM alarm_change_outbox p WHERE p.tenant_id=target.tenant_id AND p.publication_sequence IS NULL"+pendingScope.and()+realPending+") AS pending_count "+
            "FROM (SELECT CAST(? AS VARCHAR(64)) AS tenant_id) target CROSS JOIN publications LEFT JOIN alarm_change_heads h ON h.tenant_id=target.tenant_id",
            (rs,n)->new State(rs.getString("stream_id"),rs.getLong("published_head"),rs.getLong("pending_count"),rs.getLong("source_head"),rs.getLong("source_count"),rs.getLong("source_max")),stateArgs.toArray());
        if(state.head()<0||state.pending()<0||(state.stream()==null&&state.head()!=0)||state.sourceHead()!=state.sourceCount()||state.sourceHead()!=state.sourceMax())throw incomplete();
        var head=new AlarmChangeCursor.Position(tenant,identity.audience(),session,state.stream(),state.head());
        var scope=new Scope(tenant,identity.audience(),state.stream());
        var semantics=SEMANTICS;
        if(supplied==null){String encoded=AlarmChangeCursor.encode(head);return new Result("BASELINE",asOf,"Asia/Shanghai",scope,pagination.size(),List.of(),encoded,encoded,false,Long.toString(state.pending()),semantics);}
        var start=normalize(supplied,state);var end=target==null?head:normalize(target,state);
        if(start.sequence()>end.sequence())throw new ApiError(400,"CURSOR_RANGE_INVALID","变更游标范围无效");
        var itemArgs=new ArrayList<Object>();itemArgs.add(tenant);itemArgs.addAll(visible.bindings());itemArgs.add(start.sequence());itemArgs.add(end.sequence());itemArgs.add(pagination.size());
        var items=jdbc.query("WITH visible AS (SELECT pub.id,ROW_NUMBER() OVER (ORDER BY pub.publication_sequence) AS visible_sequence FROM alarm_change_outbox pub WHERE pub.tenant_id=? AND pub.publication_sequence IS NOT NULL"+visible.and()+realChange+") SELECT id,visible_sequence FROM visible WHERE visible_sequence>? AND visible_sequence<=? ORDER BY visible_sequence LIMIT ?",(rs,n)->new Item(rs.getString("id"),Long.toString(rs.getLong("visible_sequence")),"ALARM_CHANGED"),itemArgs.toArray());
        int expectedSize=(int)Math.min((long)pagination.size(),end.sequence()-start.sequence());if(items.size()!=expectedSize)throw incomplete();
        long next=start.sequence();for(var item:items){if(next==Long.MAX_VALUE||!item.sequence().equals(Long.toString(++next)))throw incomplete();}
        var nextPosition=new AlarmChangeCursor.Position(tenant,identity.audience(),session,state.stream(),next);
        return new Result("CHANGES",asOf,"Asia/Shanghai",scope,pagination.size(),items,AlarmChangeCursor.encode(nextPosition),AlarmChangeCursor.encode(end),next<end.sequence(),Long.toString(state.pending()),semantics);
    }
    private static void requireScope(AlarmChangeCursor.Position value,String tenant,String audience,String session){if(!value.tenantId().equals(tenant)||!value.audience().equals(audience)||!value.session().equals(session))throw new ApiError(400,"CURSOR_SCOPE_MISMATCH","变更游标不属于当前会话范围");}
    private static AlarmChangeCursor.Position normalize(AlarmChangeCursor.Position value,State state){
        if(value.streamId()!=null&&!value.streamId().equals(state.stream()))throw ApiError.conflict("CURSOR_RESET_REQUIRED","变更流已变化，请显式重新初始化");
        if(value.sequence()>state.head())throw ApiError.conflict("CURSOR_AHEAD","变更游标超出当前发布位置");
        return new AlarmChangeCursor.Position(value.tenantId(),value.audience(),value.session(),state.stream(),value.sequence());
    }
    private static ApiError incomplete(){return new ApiError(503,"CHANGE_STREAM_INCOMPLETE","变更流不完整，请停止推进并重新检查");}
}
