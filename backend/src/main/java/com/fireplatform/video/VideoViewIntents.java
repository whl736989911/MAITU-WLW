package com.fireplatform.video;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ApiError;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persisted video intent references for alarm workflows. Linkage writes an intent (DB only); a viewer must
 * still open an authenticated media session. No server-side playback is implied.
 */
@Service
public class VideoViewIntents {
    public record Intent(String id, String resourceId, String deviceId, String purpose, String state,
                         OffsetDateTime createdAt, OffsetDateTime expiresAt) { }

    public record AlarmVideo(VideoModels.Resource resource, Intent intent,
                             java.util.List<VideoCaptureService.Job> captures) { }

    private final JdbcTemplate jdbc;
    private final DeviceAccessScope scopes;
    private final VideoResourceQueries resources;
    private final DatabaseTime time;
    private final VideoCaptureService captures;

    public VideoViewIntents(JdbcTemplate jdbc, DeviceAccessScope scopes, VideoResourceQueries resources,
                            DatabaseTime time, VideoCaptureService captures) {
        this.jdbc = jdbc; this.scopes = scopes; this.resources = resources; this.time = time;
        this.captures = captures;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Intent queue(String tenant, String resourceId, String deviceId, String purpose,
                        String sourceKind, String sourceId, String ruleId) {
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM video_associations WHERE tenant_id=?"
            + " AND video_resource_id=? AND device_id=? AND state='ACTIVE'", Long.class, tenant, resourceId, deviceId);
        if (active == null || active == 0) throw ApiError.conflict("VIDEO_ASSOCIATION_REQUIRED", "视频未与该设备建立有效关联");
        String id = UUID.randomUUID().toString();
        var now = time.now();
        jdbc.update("INSERT INTO video_view_intents(id,tenant_id,video_resource_id,device_id,purpose,source_kind,"
                + "source_id,rule_id,state,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?, 'PENDING',?,?)",
            id, tenant, resourceId, deviceId, purpose, sourceKind, sourceId, ruleId, now, now.plusHours(4));
        return new Intent(id, resourceId, deviceId, purpose, "PENDING", now, now.plusHours(4));
    }

    @Transactional(readOnly = true)
    public List<AlarmVideo> forAlarm(String actorId, String tenant, String alarmId) {
        var devices = jdbc.query("SELECT device_id FROM alarms WHERE id=? AND tenant_id=?",
            (rs, n) -> rs.getString(1), alarmId, tenant);
        if (devices.isEmpty()) throw ApiError.missing();
        String deviceId = devices.getFirst();
        if (!scopes.contains(actorId, tenant, deviceId)) throw ApiError.missing();
        var result = new ArrayList<AlarmVideo>();
        for (VideoModels.Resource resource : resources.associatedForDevice(tenant, deviceId)) {
            var intents = jdbc.query("SELECT id,device_id,purpose,state,created_at,expires_at FROM video_view_intents"
                    + " WHERE tenant_id=? AND video_resource_id=? AND source_id=? AND source_kind='ALARM'"
                    + " ORDER BY created_at DESC,id DESC LIMIT 1",
                (rs, n) -> new Intent(rs.getString(1), resource.id(), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class)),
                tenant, resource.id(), alarmId);
            result.add(new AlarmVideo(resource, intents.isEmpty() ? null : intents.getFirst(),
                captures.recentAlarmJobs(actorId, tenant, resource.id(), deviceId, alarmId, 5)));
        }
        return List.copyOf(result);
    }
}
