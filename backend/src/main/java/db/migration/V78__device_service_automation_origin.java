package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Automation-origin commands: no fabricated platform user or password. Operator rows keep actor_id;
 * automation rows have origin=AUTOMATION, automation_source and a null actor_id. Automation idempotency
 * is keyed by (tenant_id, idem_key) because the existing (actor_id, idem_key) unique index treats null
 * actor rows as distinct.
 */
public class V78__device_service_automation_origin extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE device_service_commands ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'OPERATOR'");
            statement.execute("ALTER TABLE device_service_commands ADD COLUMN automation_source VARCHAR(200)");
            statement.execute("ALTER TABLE device_service_commands ADD CONSTRAINT ck_device_service_command_origin CHECK (origin IN ('OPERATOR','AUTOMATION'))");
        }
        try (var statement = connection.createStatement()) {
            try { statement.execute("ALTER TABLE device_service_commands ALTER COLUMN actor_id DROP NOT NULL"); }
            catch (java.sql.SQLException dropUnsupported) { statement.execute("ALTER TABLE device_service_commands ALTER COLUMN actor_id SET NULL"); }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE device_service_automation_intents (tenant_id VARCHAR(64) NOT NULL, idem_key VARCHAR(128) NOT NULL, command_id VARCHAR(64) NOT NULL REFERENCES device_service_commands(id), request_hash VARCHAR(64) NOT NULL, automation_source VARCHAR(200) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY(tenant_id,idem_key))");
            statement.execute("CREATE INDEX idx_device_service_automation_intents_command ON device_service_automation_intents(command_id)");
        }
    }
}
