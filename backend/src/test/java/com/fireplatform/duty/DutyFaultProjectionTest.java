package com.fireplatform.duty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class DutyFaultProjectionTest {
    private static final String SOURCE = "{\"parseMsg\":[{\"method\":\"thing.event.faults\",\"params\":[{\"deviceStateCode\":\"1002003\"}]}],\"platformMsg\":{\"time\":1794185853}}";

    @Test void sanitizedFormTtlFaultProjectsCodeAndSeparatesEventSourceAndReceiptTimes() throws Exception {
        ResultSet row = mock(ResultSet.class);
        OffsetDateTime created = OffsetDateTime.parse("2026-11-09T08:57:31.506429+08:00");
        OffsetDateTime received = OffsetDateTime.parse("2026-11-09T00:57:31.504522Z");
        when(row.getObject("handling_revision")).thenReturn(null);
        when(row.getString("method")).thenReturn("thing.event.faults");
        when(row.getString("params_json")).thenReturn("{\"deviceStateCode\":\"1002003\"}");
        when(row.getString("vendor_platform")).thenReturn("formttl");
        when(row.getString("payload_raw")).thenReturn(SOURCE);
        when(row.getObject("observed_at", OffsetDateTime.class)).thenReturn(null);
        when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(created);
        when(row.getObject("received_at", OffsetDateTime.class)).thenReturn(received);

        var event = DutyQueries.row(row, 0);
        assertThat(event.faultCode()).isEqualTo("1002003");
        assertThat(event.faultLabel()).isEqualTo("防拆报警");
        assertThat(event.observedAt()).isNull();
        assertThat(event.sourceMessageAt()).isEqualTo(OffsetDateTime.parse("2026-11-09T00:57:33Z"));
        assertThat(event.sourceTimeField()).isEqualTo("platformMsg.time");
        assertThat(event.receivedAt()).isEqualTo(received);
        assertThat(event.createdAt()).isEqualTo(created);
    }

    @Test void platformTimeIsNotProjectedForOtherSourcesOrNonNumericValues() {
        assertThat(DutyQueries.sourceMessageTime("formttl", "{\"platformMsg\":{\"time\":\"1794185853\"}}"))
            .isEqualTo(new DutyQueries.SourceMessageTime(null, null));
        assertThat(DutyQueries.sourceMessageTime("other", SOURCE))
            .isEqualTo(new DutyQueries.SourceMessageTime(null, null));
    }
}
