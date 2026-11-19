package db.migration;

import java.util.ArrayList;
import java.util.regex.Pattern;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Extend the legacy FIRE-only notification event scope without weakening its existing rule. */
public class V62__fault_notification_routing extends BaseJavaMigration {
    private record Check(String name, String expression) { }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var eventScopeChecks = new ArrayList<Check>();
        try (var query = connection.prepareStatement(
                "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog "
                        + "AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name "
                        + "WHERE LOWER(tc.table_name)='notification_rules' AND tc.table_schema=? "
                        + "AND tc.constraint_type='CHECK'")) {
            query.setString(1, connection.getSchema());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String expression = rows.getString(2);
                    if (expression != null
                            && Pattern.compile("(?i)\\bevent_scope\\b").matcher(expression).find()
                            && Pattern.compile("(?i)\\bFIRE\\b").matcher(expression).find()) {
                        eventScopeChecks.add(new Check(rows.getString(1), expression));
                    }
                }
            }
        }
        if (eventScopeChecks.size() != 1) {
            throw new IllegalStateException("Expected one notification event_scope check; refusing an ambiguous migration");
        }
        Check eventScopeCheck = eventScopeChecks.getFirst();
        String identifier = "\"" + eventScopeCheck.name().replace("\"", "\"\"") + "\"";
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE notification_rules DROP CONSTRAINT " + identifier);
            statement.execute("ALTER TABLE notification_rules ADD CONSTRAINT notification_rules_event_scope_check CHECK (("
                    + eventScopeCheck.expression() + ") OR event_scope='FAULT')");
            statement.execute("ALTER TABLE notification_provider_attempts ADD COLUMN event_kind VARCHAR(8) NOT NULL DEFAULT 'FIRE' CHECK (event_kind IN ('FIRE','FAULT'))");
            statement.execute("ALTER TABLE notification_provider_attempts ADD COLUMN source_kind VARCHAR(24) NOT NULL DEFAULT 'ALARM' CHECK (source_kind IN ('ALARM','RAW_FAULT','LEGACY_FAULT'))");
            statement.execute("ALTER TABLE notification_provider_attempts ADD COLUMN source_id VARCHAR(64)");
            statement.execute("UPDATE notification_provider_attempts SET source_id=alarm_id");
            statement.execute("ALTER TABLE notification_provider_attempts ALTER COLUMN alarm_id DROP NOT NULL");
            statement.execute("ALTER TABLE notification_provider_attempts ADD CONSTRAINT notification_provider_attempts_source_check CHECK ((event_kind='FIRE' AND source_kind='ALARM' AND alarm_id IS NOT NULL AND source_id=alarm_id) OR (event_kind='FAULT' AND source_kind IN ('RAW_FAULT','LEGACY_FAULT') AND alarm_id IS NULL AND source_id IS NOT NULL))");
            statement.execute("ALTER TABLE notification_provider_attempts ADD CONSTRAINT notification_provider_attempts_source_attempt_key UNIQUE (tenant_id,event_kind,source_kind,source_id,occurrence_key,contact_id,channel,attempt_no)");
            statement.execute("CREATE INDEX idx_notification_attempt_source ON notification_provider_attempts(tenant_id,event_kind,source_id,created_at DESC)");
        }
    }
}
