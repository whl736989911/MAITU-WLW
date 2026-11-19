package com.fireplatform.configuration;

import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Fixed read-only projection over caller's d(tenant_id,id). Never interpolate request values. */
@Component
public class OfflinePolicySql {
    public static final String KEY="offline_threshold_minutes", CONSUMER="CONNECTIVITY_AND_OFFLINE_EVENTS";
    private static final String PLACE_POLICY = """
        LEFT JOIN (
          WITH RECURSIVE policy_tree(tenant_id,place_id,scope_id,override_value,revision,distance) AS (
            SELECT p.tenant_id,p.id,o.scope_id,o.override_value,o.revision,0
            FROM parameter_overrides o JOIN places p ON p.id=o.scope_id AND p.tenant_id=o.tenant_id
            WHERE o.scope='PLACE' AND o.parameter_key='offline_threshold_minutes'
              AND o.override_value IS NOT NULL AND p.archived_at IS NULL
            UNION ALL
            SELECT child.tenant_id,child.id,parent.scope_id,parent.override_value,parent.revision,parent.distance+1
            FROM places child JOIN policy_tree parent
              ON child.tenant_id=parent.tenant_id AND child.parent_place_id=parent.place_id
            WHERE child.archived_at IS NULL
          )
          SELECT tenant_id,place_id,scope_id,override_value,revision,
                 ROW_NUMBER() OVER (PARTITION BY tenant_id,place_id ORDER BY distance) AS nearest
          FROM policy_tree
        ) pol_place ON pol_place.tenant_id=d.tenant_id AND pol_place.place_id=d.place_id AND pol_place.nearest=1
        """;
    private final boolean postgres;
    public OfflinePolicySql(RuntimeConfig runtime) { postgres=runtime.storage().equals("postgresql"); }
    public String joins() {
        return " "+"""
            CROSS JOIN (SELECT CAST(? AS TIMESTAMP WITH TIME ZONE) AS evaluated_at) pol_clock
            JOIN numeric_settings pol_global ON pol_global.setting_key='offline_threshold_minutes'
            LEFT JOIN parameter_overrides pol_tenant ON pol_tenant.tenant_id=d.tenant_id AND pol_tenant.scope='TENANT'
                AND pol_tenant.scope_id=d.tenant_id AND pol_tenant.parameter_key='offline_threshold_minutes'
            LEFT JOIN parameter_overrides pol_device ON pol_device.tenant_id=d.tenant_id AND pol_device.scope='DEVICE'
                AND pol_device.scope_id=d.id AND pol_device.parameter_key='offline_threshold_minutes'
            """+PLACE_POLICY;
    }
    public String connectivityJoins() { return joins()+" LEFT JOIN (SELECT tenant_id,device_id,MAX(observed_at) AS last_heartbeat_at FROM legacy_ingest_events WHERE kind='HEARTBEAT' AND observed_at IS NOT NULL GROUP BY tenant_id,device_id) pol_heartbeat ON pol_heartbeat.tenant_id=d.tenant_id AND pol_heartbeat.device_id=d.id "; }
    public String columns() { return "pol_global.setting_value AS policy_global_value,pol_global.revision AS policy_global_revision,pol_tenant.override_value AS policy_tenant_value,pol_tenant.revision AS policy_tenant_revision,pol_place.override_value AS policy_place_value,pol_place.scope_id AS policy_place_id,pol_place.revision AS policy_place_revision,pol_device.override_value AS policy_device_value,pol_device.revision AS policy_device_revision,pol_clock.evaluated_at AS policy_evaluated_at"; }
    public String value() { return "COALESCE(pol_device.override_value,pol_place.override_value,pol_tenant.override_value,pol_global.setting_value)"; }
    public String cutoff() { return postgres ? "(pol_clock.evaluated_at - ("+value()+" * INTERVAL '1 minute'))" : "DATEADD('MINUTE', -"+value()+", pol_clock.evaluated_at)"; }
    public String online() { return "d.last_message_at >= "+cutoff(); }
    public String offline() { return "pol_heartbeat.last_heartbeat_at IS NOT NULL AND d.last_message_at < "+cutoff(); }
    public String unconfirmedTimeout() { return "pol_heartbeat.last_heartbeat_at IS NULL AND d.last_message_at < "+cutoff(); }
    public String connectivity() { return "CASE WHEN d.last_message_at IS NULL THEN 'UNACTIVATED' WHEN "+online()+" THEN 'ONLINE' WHEN "+offline()+" THEN 'OFFLINE' ELSE 'UNKNOWN' END"; }
    public ParameterModels.Effective effective(ResultSet rs,String tenant,String device) throws SQLException {
        Long own=rs.getObject("policy_device_value",Long.class), place=rs.getObject("policy_place_value",Long.class), unit=rs.getObject("policy_tenant_value",Long.class);
        String scope=own!=null?"DEVICE":place!=null?"PLACE":unit!=null?"TENANT":"GLOBAL";
        return new ParameterModels.Effective(KEY,own!=null?own:place!=null?place:unit!=null?unit:rs.getLong("policy_global_value"),
            com.fireplatform.shared.Limits.SETTINGS.get(KEY).unit(),scope,own!=null?device:place!=null?rs.getString("policy_place_id"):unit!=null?tenant:null,
            rs.getLong("policy_"+scope.toLowerCase(Locale.ROOT)+"_revision"),rs.getObject("policy_evaluated_at",java.time.OffsetDateTime.class));
    }
    public List<ParameterModels.ChainEntry> chain(ResultSet rs,String tenant,String device,ParameterModels.Effective effective) throws SQLException {
        var rows=new ArrayList<ParameterModels.ChainEntry>(); rows.add(new ParameterModels.ChainEntry("GLOBAL",null,true,rs.getLong("policy_global_value"),rs.getLong("policy_global_revision"),effective.sourceScope().equals("GLOBAL")));
        Long parent=rs.getObject("policy_tenant_value",Long.class); rows.add(new ParameterModels.ChainEntry("TENANT",tenant,parent!=null,parent,rs.getLong("policy_tenant_revision"),effective.sourceScope().equals("TENANT")));
        Long place=rs.getObject("policy_place_value",Long.class);
        if(place!=null)rows.add(new ParameterModels.ChainEntry("PLACE",rs.getString("policy_place_id"),true,place,rs.getLong("policy_place_revision"),effective.sourceScope().equals("PLACE")));
        if(device!=null) { Long own=rs.getObject("policy_device_value",Long.class); rows.add(new ParameterModels.ChainEntry("DEVICE",device,own!=null,own,rs.getLong("policy_device_revision"),effective.sourceScope().equals("DEVICE"))); } return List.copyOf(rows);
    }
}
