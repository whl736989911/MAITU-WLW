package com.fireplatform.duty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.ScopeFilter;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

class DutyQueriesAggregateTest {
    @Test void deviceSummaryCountsAllEventsAndTypesBeforeDevicePagination() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DeviceAccessScope access = mock(DeviceAccessScope.class);
        ScopeFilter scope = mock(ScopeFilter.class);
        when(access.predicate("actor", "e.tenant_id", "e.device_id"))
            .thenReturn(new DeviceAccessScope.Filter("1=1", List.of()));
        when(scope.alarmDevices("e", List.of(), List.of()))
            .thenReturn(new ScopeFilter.Clause("1=1", List.of()));
        doReturn(1L).when(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));

        ResultSet group = mock(ResultSet.class);
        OffsetDateTime received = OffsetDateTime.parse("2026-11-09T00:57:31.504522Z");
        when(group.getString("tenant_id")).thenReturn("tenant-a");
        when(group.getString("device_id")).thenReturn("device-a");
        when(group.getString("name")).thenReturn("Smoke detector");
        when(group.getString("address")).thenReturn("Place A");
        when(group.getString("place_id")).thenReturn("place-a");
        when(group.getLong("event_count")).thenReturn(7L);
        when(group.getObject("latest_observed_at", OffsetDateTime.class)).thenReturn(null);
        when(group.getObject("latest_received_at", OffsetDateTime.class)).thenReturn(received);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") RowMapper<DutyQueries.DeviceFaultSummary> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(group, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        ResultSet fault = mock(ResultSet.class);
        when(fault.getString("tenant_id")).thenReturn("tenant-a");
        when(fault.getString("device_id")).thenReturn("device-a");
        when(fault.getString("method")).thenReturn("thing.event.faults");
        when(fault.getString("params_json")).thenReturn("{\"deviceStateCode\":\"1002003\"}");
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertThat(sql).doesNotContain("LIMIT");
            RowCallbackHandler callback = invocation.getArgument(1);
            for (int i = 0; i < 7; i++) callback.processRow(fault);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        var page = new DutyQueries(jdbc, access, scope, (PageSizePolicy) () -> 20)
            .deviceFaults("actor", "tenant-a", ScopeFilter.Selection.all(), "", null, null, 1, null);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(device -> {
            assertThat(device.eventCount()).isEqualTo(7);
            assertThat(device.latestObservedAt()).isNull();
            assertThat(device.latestReceivedAt()).isEqualTo(received);
            assertThat(device.faultTypes()).containsExactly(new DutyQueries.FaultTypeCount("1002003", "防拆报警", 7));
        });
    }
}
