package com.fireplatform.notification;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.sql.Timestamp;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NotificationTimestampTest {
    @Test void pgTimestampRowsRetainMicrosecondsRatherThanFallingThroughLegacyDateMilliseconds()throws Exception{var timestamp=Timestamp.from(Instant.parse("2026-10-23T00:00:00.000001Z"));var safe=NotificationReads.pick(Map.of("created_at",timestamp),"created_at");assertThat(safe.get("created_at")).isEqualTo(OffsetDateTime.parse("2026-10-23T00:00:00.000001Z"));var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);assertThat(mapper.writeValueAsString(safe)).contains("2026-10-23T00:00:00.000001Z");}
}
