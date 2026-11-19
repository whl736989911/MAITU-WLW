package com.fireplatform.legacyingest;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** A moved asset cannot inherit undated or pre-cutover packets just because its EUI matches. */
public final class LegacyOwnershipBoundary {
    private LegacyOwnershipBoundary() { }
    public record Rejection(String transferId,String reason) { }
    private record Boundary(String id,OffsetDateTime at) { }
    public static Rejection check(JdbcTemplate jdbc,String deviceId,OffsetDateTime observedAt,List<LegacyNormalizedEvent.LegacyEvent> events) {
        var rows=jdbc.query("SELECT t.id,COALESCE(d.ownership_from,t.occurred_at) FROM devices d LEFT JOIN device_transfers t ON t.target_device_id=d.id WHERE d.id=? AND (d.ownership_from IS NOT NULL OR t.id IS NOT NULL) ORDER BY t.occurred_at DESC",
            (r,n)->new Boundary(r.getString(1),r.getObject(2,OffsetDateTime.class)),deviceId);
        if(rows.isEmpty())return null;
        var boundary=rows.getFirst();
        if(events.isEmpty())return reject(boundary,observedAt);
        for(var event:events) {
            var rejection=reject(boundary,event.observedAt()==null?observedAt:event.observedAt());
            if(rejection!=null)return rejection;
        }
        return null;
    }
    private static Rejection reject(Boundary boundary,OffsetDateTime at) {
        if(at==null)return new Rejection(boundary.id(),"OWNERSHIP_TIME_UNKNOWN");
        return at.isBefore(boundary.at())?new Rejection(boundary.id(),"PRE_OWNERSHIP_HISTORY"):null;
    }
}
