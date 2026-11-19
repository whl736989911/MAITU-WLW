package com.fireplatform.legacyingest.facts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.legacyingest.FaultProjection;
import com.fireplatform.shared.NotificationSuppressionSql;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DeviceStatusComposer {
    public enum Code { OFFLINE, FIRE, DISABLED, SHIELDED, FAULT, ONLINE, UNACTIVATED }
    public record BusinessStatus(Code code,String label) { }
    public record Status(Code onlineStatus,Code customerStatus,List<BusinessStatus> businessStatuses) { }
    private final ObjectMapper json;
    private final String array;

    public DeviceStatusComposer(ObjectMapper json,RuntimeConfig runtime) {
        this.json=json;
        this.array="postgresql".equals(runtime.storage())?"json_agg":"JSON_ARRAYAGG";
    }

    /** Only device-level notification rules apply to this badge; a channel rule never shields a whole device. */
    public String columns() {
        return """
            , EXISTS(SELECT 1 FROM alarms a WHERE a.tenant_id=d.tenant_id AND a.device_id=d.id
                     AND a.event_type='FIRE' AND a.response_state='UNHANDLED' AND a.is_simulated=FALSE) AS pending_alarm
            , EXISTS(SELECT 1 FROM device_suppressions s WHERE s.tenant_id=d.tenant_id AND s.kind='NOTIFICATION'
                     AND s.resource_kind='DEVICE' AND s.fire_confirmed=TRUE AND
            """+NotificationSuppressionSql.activeAt("s","pol_clock.evaluated_at")+"""
                     AND (s.scope='TENANT' OR EXISTS(SELECT 1 FROM device_suppression_members m
                       WHERE m.suppression_id=s.id AND m.tenant_id=d.tenant_id AND m.device_id=d.id))) AS notification_shielded
            , (SELECT
            """+array+"""
                (CAST(e.params_json AS VARCHAR) ORDER BY e.observed_at DESC NULLS LAST,e.created_at DESC,e.id)
                 FROM legacy_ingest_events e
                 WHERE e.tenant_id=d.tenant_id AND e.device_id=d.id AND e.kind='FAULT'
                   AND NOT EXISTS(SELECT 1 FROM duty_fault_handlings h
                       WHERE h.tenant_id=e.tenant_id AND h.event_id=e.id AND h.handled=TRUE)) AS pending_faults
            """;
    }

    public Status compose(Code online,boolean pendingAlarm,boolean notificationShielded,String faultPayloads) {
        var statuses=new ArrayList<BusinessStatus>();
        if(pendingAlarm)statuses.add(new BusinessStatus(Code.FIRE,"报警"));
        if(notificationShielded)statuses.add(new BusinessStatus(Code.SHIELDED,"屏蔽"));
        if(faultPayloads!=null) {
            var faults=new LinkedHashSet<String>();
            try {
                for(var payload:json.readTree(faultPayloads)) {
                    JsonNode params=readFault(payload.textValue());
                    faults.add(FaultProjection.describe(params).label());
                }
            } catch(JsonProcessingException invalidProjection) {
                throw new IllegalStateException("Invalid fault aggregate",invalidProjection);
            }
            for(String fault : faults)statuses.add(new BusinessStatus(Code.FAULT,fault));
        }
        Code primary=online==Code.OFFLINE||statuses.isEmpty()?online:statuses.getFirst().code();
        return new Status(online,primary,List.copyOf(statuses));
    }

    private JsonNode readFault(String payload) {
        if(payload==null)return null;
        try { return json.readTree(payload); }
        catch(JsonProcessingException ignored) { return null; }
    }
}
