package com.fireplatform.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.shared.ApiError;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MediaCookieTenantDisableAuthorizationTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void issuedMediaCookieIsRejectedWhenTenantIsDisabled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DatabaseTime time = mock(DatabaseTime.class);
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(time.now()).thenReturn(now);
        Object[] session = {"session", "tenant", "resource", "device", "PLAYBACK", now.plusMinutes(5),
            "ACTIVE", "HTTPS_HLS", true, null, "endpoint-cipher", "endpoint-nonce", null, null,
            null, null, true, false, "CUSTOMER", "tenant"};
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.<Object[]>of(session));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
            .thenReturn(0L);
        VideoMediaService service = new VideoMediaService(jdbc, null, null, null, null, null,
            null, time, new ObjectMapper(), null);

        assertThatThrownBy(() -> service.validate("customer", "resource", "fixture-cookie", "PLAYBACK"))
            .isInstanceOfSatisfying(ApiError.class, error -> assertThat(error.code).isEqualTo("UNIT_DISABLED"));
    }
}
