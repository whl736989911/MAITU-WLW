package com.fireplatform.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class HlsCrossOriginCredentialRegressionTest {
    private static final String PRIMARY = "https://primary.example/live/index.m3u8";

    private static final String AUTH = "Basic " + java.util.Base64.getEncoder()
        .encodeToString("fixture-user:fixture-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void hlsOnlyForwardsBasicToExactPrimaryOrigin() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VideoSecretCipher cipher = mock(VideoSecretCipher.class);
        VideoPolicy policy = mock(VideoPolicy.class);
        RecordingUpstream upstream = new RecordingUpstream(policy);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.<String[]>of(new String[]{"fixture-ciphertext", "fixture-nonce"}));
        when(cipher.decrypt(anyString(), anyString(), anyString()))
            .thenReturn("https://secondary.example/live/segment.ts")
            .thenReturn("https://primary.example/live/segment.ts");
        when(policy.maxSubresourceBytes()).thenReturn(1024L);
        VideoMediaService media = new VideoMediaService(jdbc, null, policy, cipher, upstream,
            null, null, null, new ObjectMapper(), null);
        var context = new VideoMediaService.Context("fixture-session", "customer", "fixture-tenant",
            "fixture-resource", "fixture-device", "HTTPS_HLS", "PLAYBACK", PRIMARY,
            "fixture-user", "fixture-password");

        try (var crossOrigin = media.openHls(context, 0)) {
            assertThat(upstream.authorization()).isNull();
        }
        try (var sameOrigin = media.openHls(context, 0)) {
            assertThat(upstream.authorization()).isEqualTo(AUTH);
        }
    }

    private static final class RecordingUpstream extends VideoUpstreamClient {
        private final AtomicReference<String> authorization = new AtomicReference<>();
        RecordingUpstream(VideoPolicy policy) { super(policy); }
        @Override public Target resolve(String value) {
            URI uri = URI.create(value);
            return new Target(uri, uri.getHost());
        }
        @Override public Upstream open(Target target, String range, String authorization, long maximum) {
            this.authorization.set(authorization);
            return new Upstream(new Socket(), 200, "video/mp2t", 4L, null, false,
                new ByteArrayInputStream(new byte[]{0x47, 0x40, 0x11, 0x10}));
        }
        String authorization() { return authorization.get(); }
    }
}
