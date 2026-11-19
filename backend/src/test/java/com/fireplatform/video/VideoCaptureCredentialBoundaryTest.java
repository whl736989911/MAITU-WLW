package com.fireplatform.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.net.URI;
import org.junit.jupiter.api.Test;

class VideoCaptureCredentialBoundaryTest {
    @Test
    void captureDoesNotForwardPrimaryBasicToCrossOriginHlsResources() {
        RecordingUpstream upstream = new RecordingUpstream();
        VideoCaptureService service = new VideoCaptureService(null, null, null, null, upstream, null,
            null, null, null, null);
        String authorization = "Basic fixture-credential";

        service.fetch("https://secondary.example/live/segment.ts", "https://primary.example/live/index.m3u8",
            authorization, 1024);
        assertThat(upstream.authorization).isNull();
        service.fetch("https://primary.example/live/segment.ts", "https://primary.example/live/index.m3u8",
            authorization, 1024);
        assertThat(upstream.authorization).isEqualTo(authorization);
    }

    private static final class RecordingUpstream extends VideoUpstreamClient {
        private String authorization;
        RecordingUpstream() { super(mock(VideoPolicy.class)); }
        @Override public Target resolve(String value) {
            URI uri = URI.create(value);
            return new Target(uri, uri.getHost());
        }
        @Override public Upstream open(Target target, String range, String authorization, long maximum) {
            this.authorization = authorization;
            return new Upstream(new Socket(), 200, "video/mp2t", 1L, null, false,
                new ByteArrayInputStream(new byte[]{0x47}));
        }
    }
}
