package com.fireplatform.processing;

import com.fireplatform.shared.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessingQueries {
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes; private final DeviceAccessScope scope; private final ScopeFilter scopes;
    public ProcessingQueries(JdbcTemplate jdbc,PageSizePolicy sizes,DeviceAccessScope scope,ScopeFilter scopes) { this.jdbc=jdbc; this.sizes=sizes; this.scope=scope; this.scopes=scopes; }
    ProcessingModels.Task row(ResultSet rs) throws SQLException {
        String result=rs.getString("result_json"); return new ProcessingModels.Task(rs.getString("id"),rs.getString("kind"),rs.getString("tenant_id"),rs.getString("raw_message_id"),rs.getString("source_id"),rs.getString("source_message_id"),rs.getString("device_id"),rs.getString("device_name"),rs.getString("product_version_id"),rs.getString("actor_id"),rs.getLong("expected_device_revision"),rs.getString("recovery_alarm_id"),rs.getString("reason"),rs.getString("status"),rs.getLong("revision"),rs.getInt("attempt_count"),rs.getInt("max_attempts"),rs.getObject("available_at",OffsetDateTime.class),rs.getString("lease_owner"),rs.getObject("lease_until",OffsetDateTime.class),rs.getLong("lease_token"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("finished_at",OffsetDateTime.class),rs.getString("last_error_code"),result);
    }
    ProcessingModels.Task stored(String actor,String tenant,String id) {
        var f=scope.predicate(actor,"processing_tasks.tenant_id","processing_tasks.device_id");
        var args=new ArrayList<Object>(List.of(tenant,id));args.addAll(f.bindings());
        var rows=jdbc.query("SELECT * FROM processing_tasks WHERE tenant_id=? AND id=?"+f.and(),(rs,n)->row(rs),args.toArray());
        if(rows.isEmpty())throw ApiError.missing();return rows.getFirst();
    }
    @Transactional(readOnly=true) public Page<ProcessingModels.Task> list(String actor,String tenant,ScopeFilter.Selection selection,int page,Integer size,String keyword,String status) {
        var p=Pagination.of(page,size,sizes.defaultPageSize()); if(keyword==null) keyword=""; if(keyword.length()>Limits.MAX_KEYWORD_LENGTH) throw ApiError.bad("搜索关键字过长");
        var unitScope=scopes.units(actor,"processing_tasks","tenant_id",tenant,selection);
        var deviceScope=scope.predicate(actor,"processing_tasks.tenant_id","processing_tasks.device_id");
        String like="%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String where=" FROM processing_tasks WHERE "+unitScope.sql()+deviceScope.and(); var args=new ArrayList<Object>(unitScope.bindings());args.addAll(deviceScope.bindings());
        if(!selection.tenantIds().isEmpty()||!selection.placeIds().isEmpty()) {
            var selectedDevices=scopes.devices(actor,"d",tenant,selection);
            where+=" AND EXISTS (SELECT 1 FROM devices d WHERE d.tenant_id=processing_tasks.tenant_id AND d.id=processing_tasks.device_id AND "+selectedDevices.sql()+")";
            args.addAll(selectedDevices.bindings());
        }
        if(status!=null) { if(!Set.of("PENDING","RUNNING","SUCCEEDED","REJECTED","CANCELLED").contains(status)) throw ApiError.bad("任务状态过滤无效"); where+=" AND status=?"; args.add(status); }
        where+=" AND (LOWER(id) LIKE ? ESCAPE '!' OR LOWER(source_message_id) LIKE ? ESCAPE '!' OR LOWER(device_name) LIKE ? ESCAPE '!')"; args.add(like); args.add(like); args.add(like);
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray()); args.add(p.size()); args.add(p.offset()); var rows=jdbc.query("SELECT *"+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(rs,n)->row(rs),args.toArray()); return new Page<>(rows,total,p.page(),p.size());
    }
    @Transactional(readOnly=true) public ProcessingModels.Detail detail(String actor,String tenant,String id) { var task=stored(actor,tenant,id); var attempts=jdbc.query("SELECT * FROM processing_attempts WHERE task_id=? ORDER BY attempt_no",(rs,n)->new ProcessingModels.Attempt(rs.getString("id"),rs.getInt("attempt_no"),rs.getLong("lease_token"),rs.getString("worker_id"),rs.getObject("started_at",OffsetDateTime.class),rs.getObject("finished_at",OffsetDateTime.class),rs.getString("outcome"),rs.getString("error_code")),id); return new ProcessingModels.Detail(task,attempts); }
}
