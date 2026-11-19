package com.fireplatform.bulkio;

import com.fireplatform.alarmgroup.AlarmSessionQueries;
import com.fireplatform.identity.AuthService;
import com.fireplatform.shared.ApiError;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Derived sessions are exportable evidence, never an import route for fabricated alarms or sent claims. */
@Component
public class AlarmSessionDataTransferDomain implements DataTransferDomain {
    private static final String KIND="ALARM_SESSIONS";
    private static final List<String> COLUMNS=List.of("id","tenant_id","device_id","device_name","event_type",
        "policy_revision","policy_state","window_seconds","first_source_at","last_source_at","window_ends_at",
        "open","closed_at","member_count","active_member_count","unhandled_member_count","created_at");
    private final AlarmSessionQueries queries;
    public AlarmSessionDataTransferDomain(AlarmSessionQueries queries){this.queries=queries;}
    @Override public List<Kind> kinds(){return List.of(new Kind(KIND,List.of(),"alarm:read",null,false,true,false,Set.of("management","customer"),"READ_ONLY"));}
    @Override public ExportData export(String kind,AuthService.Identity actor,String tenant,Map<String,String> filters,List<String> selectedIds) {
        if(!KIND.equals(kind))throw ApiError.bad("不支持的会话导出类型");
        var f=filters==null?Map.<String,String>of():filters;
        var selection=DataTransferFilters.selection(f,Set.of("device_id","event_type"));
        String device=f.get("device_id"),event=f.get("event_type");
        if(device!=null&&device.length()>64||event!=null&&event.length()>64)throw ApiError.bad("会话导出筛选无效");
        var ids=selectedIds==null?null:DataTransferFilters.selectedIds(selectedIds);
        var sessions=queries.exportSessions(actor.user().id(),tenant,selection,device,event,ids);
        var rows=sessions.stream().map(s->List.of(s.id(),s.tenantId(),s.deviceId(),cell(s.deviceName()),s.eventType(),
            Long.toString(s.policyRevision()),s.policyState(),Integer.toString(s.windowSeconds()),cell(s.firstSourceAt()),
            cell(s.lastSourceAt()),cell(s.windowEndsAt()),Boolean.toString(s.open()),cell(s.closedAt()),Long.toString(s.memberCount()),
            Long.toString(s.activeMemberCount()),Long.toString(s.unhandledMemberCount()),cell(s.createdAt()))).toList();
        return new ExportData(COLUMNS,rows);
    }
    private static String cell(Object value){return Objects.toString(value,"");}
}
