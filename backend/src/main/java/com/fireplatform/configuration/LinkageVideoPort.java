package com.fireplatform.configuration;

import java.util.List;

/**
 * Outbound seam owned by alarm-policy evaluation. Configuration reads associated video resources and records
 * durable capture/view intents through this port; it never imports the video module.
 *
 * <p>Evaluation performs no media I/O: a capture job or view intent is a persisted reference, and the viewer or
 * worker still has to prove the media path.</p>
 */
public interface LinkageVideoPort {
    /** Linkage-time projection of one associated resource; no endpoint, signed URL or credential is exposed. */
    record Video(String id, String name, String protocol, String playback, String snapshot, boolean mediaReady,
                 String note) { }

    record Capture(String id, String state) { }

    record Intent(String id, String purpose, String state) { }

    List<Video> associatedForDevice(String tenant, String deviceId);

    Capture queueCapture(String tenant, String videoId, String deviceId, String sourceKind, String sourceId,
                         String ruleId);

    Intent queueIntent(String tenant, String videoId, String deviceId, String purpose, String sourceKind,
                       String sourceId, String ruleId);
}
