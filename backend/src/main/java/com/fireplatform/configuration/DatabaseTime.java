package com.fireplatform.configuration;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Wall-clock at the executing SQL statement, never PG's transaction-stable now(). */
@Service
public class DatabaseTime {
    private final JdbcTemplate jdbc; private final String expression;
    public DatabaseTime(JdbcTemplate jdbc,RuntimeConfig config) { this.jdbc=jdbc; expression=config.storage().equals("postgresql") ? "clock_timestamp()" : "(SELECT EXECUTING_STATEMENT_START FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID=SESSION_ID())"; }
    public String expression() { return expression; }
    // Stored timestamps have microsecond precision: floor DB wall time, never round immediate availability into the future.
    public OffsetDateTime now() { return jdbc.queryForObject("SELECT "+expression,OffsetDateTime.class).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
}
