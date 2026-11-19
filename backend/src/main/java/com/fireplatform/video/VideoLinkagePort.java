package com.fireplatform.video;

import com.fireplatform.configuration.LinkageVideoPort;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Implements the configuration-owned linkage seam by delegating to the existing video queries and durable
 * intent writers. Evaluation never fetches media; this adapter only exposes the linkage-time projection and
 * persists the same capture/view references the video module already owns.
 */
@Component
public class VideoLinkagePort implements LinkageVideoPort {
    private final VideoResourceQueries resources;
    private final VideoCaptureService captures;
    private final VideoViewIntents intents;

    public VideoLinkagePort(VideoResourceQueries resources, VideoCaptureService captures, VideoViewIntents intents) {
        this.resources = resources;
        this.captures = captures;
        this.intents = intents;
    }

    @Override
    public List<Video> associatedForDevice(String tenant, String deviceId) {
        return resources.associatedForDevice(tenant, deviceId).stream()
            .map(resource -> new Video(resource.id(), resource.name(), resource.protocol(), resource.playback(),
                resource.snapshot(), resource.mediaReady(), resource.note()))
            .toList();
    }

    @Override
    public Capture queueCapture(String tenant, String videoId, String deviceId, String sourceKind, String sourceId,
                                String ruleId) {
        var job = captures.queueFromLinkage(tenant, videoId, deviceId, sourceKind, sourceId, ruleId);
        return new Capture(job.id(), job.state());
    }

    @Override
    public Intent queueIntent(String tenant, String videoId, String deviceId, String purpose, String sourceKind,
                              String sourceId, String ruleId) {
        var intent = intents.queue(tenant, videoId, deviceId, purpose, sourceKind, sourceId, ruleId);
        return new Intent(intent.id(), intent.purpose(), intent.state());
    }
}
