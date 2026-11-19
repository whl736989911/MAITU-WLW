package com.fireplatform.alarm;

import java.time.OffsetDateTime;
import java.util.*;

public final class AlarmTraceModels {
    private AlarmTraceModels() { }
    public record State(String physicalState,String responseState,String conclusion) { }
    public record Coverage(String kind,Long firstRevision,Long journalThroughRevision,long currentRevision,long entryTotal,OffsetDateTime baselineRecordedAt) { }
    public record History(List<Map<String,Object>> items,long total,int page,int size,Coverage coverage) { }
    record Stored(String id,String alarmId,String action,Long revisionBefore,long revisionAfter,State before,State after,OffsetDateTime occurredAt,OffsetDateTime recordedAt,String actorKind,String actorId,String actorName,String remark,String origin) { }
    static Map<String,Object> project(Stored entry,String viewer,boolean management) {
        var row=new LinkedHashMap<String,Object>(); boolean self=entry.actorId()!=null && entry.actorId().equals(viewer);
        String generic=switch(entry.actorKind()) { case "PLATFORM" -> "平台人员"; case "CUSTOMER" -> "单位人员"; case "SYSTEM" -> "系统处理"; default -> "历史未知"; };
        String label=management?(entry.actorName()!=null?entry.actorName():generic):self?"本人":generic;
        row.put("id",entry.id()); row.put("alarm_id",entry.alarmId()); row.put("action",entry.action()); row.put("revision_before",entry.revisionBefore()); row.put("revision_after",entry.revisionAfter()); row.put("before",entry.before()); row.put("after",entry.after()); row.put("occurred_at",entry.occurredAt()); row.put("recorded_at",entry.recordedAt()); row.put("actor_kind",entry.actorKind()); row.put("actor_label",label); row.put("is_self",self); row.put("remark",entry.remark()); row.put("origin",entry.origin());
        if(management) { row.put("actor_id",entry.actorId()); row.put("actor_name",entry.actorName()); } return Collections.unmodifiableMap(row);
    }
}
