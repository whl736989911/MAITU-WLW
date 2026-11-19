package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Adds the real WeChat Official Account channel without changing SMS/VOICE billing, resources or
 * historical attempt semantics.
 *
 * <p>The existing channel check constraints are discovered by expression instead of guessed names so
 * the same migration works on H2 development databases and PostgreSQL production databases.</p>
 */
public class V83__wechat_official_account extends BaseJavaMigration {
    private record Check(String name, String expression) { }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String schema = connection.getSchema();

        // Receivers stored WECHAT as a six-character value: drop the dependent check, widen, then restore it.
        Check receiverChannelCheck = findCheck(connection, schema, "notification_rule_receivers",
            List.of("channel", "'sms'", "'voice'"), List.of());
        if (receiverChannelCheck == null) throw new IllegalStateException("Expected notification_rule_receivers channel check");
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE notification_rule_receivers DROP CONSTRAINT " + quote(receiverChannelCheck.name()));
            statement.execute("ALTER TABLE notification_rule_receivers ALTER COLUMN channel TYPE VARCHAR(8)");
            statement.execute("ALTER TABLE notification_rule_receivers ADD CONSTRAINT notification_rule_receivers_channel_check CHECK (channel IN ('SMS','VOICE','WECHAT'))");
        }
        replaceCheck(connection, schema, "notification_provider_attempts", List.of("channel", "'sms'", "'voice'"), List.of("state", "status", "event_kind"), "notification_provider_attempts_channel_check",
            "channel IN ('SMS','VOICE','WECHAT')");
        replaceCheck(connection, schema, "alarm_notification_claims", List.of("channel", "'sms'", "'voice'"), List.of(), "alarm_notification_claims_channel_check",
            "channel IN ('SMS','VOICE','WECHAT')");

        // Receipts and observations carry both a channel and a state check; replace them together.
        replaceCheck(connection, schema, "notification_delivery_receipts", List.of("channel", "'sms'", "'voice'"), List.of("state"), "notification_delivery_receipts_channel_check",
            "channel IN ('SMS','VOICE','WECHAT')");
        replaceCheck(connection, schema, "notification_delivery_receipts", List.of("channel", "'sms'", "'voice'", "state"), List.of(), "notification_delivery_receipts_state_check",
            "(channel='SMS' AND state IN ('UNCONFIRMED','WAITING','DELIVERED','FAILED'))"
                + " OR (channel='VOICE' AND state IN ('UNCONFIRMED','WAITING','HEARD_FULL','CONNECTED_PARTIAL','NOT_CONNECTED'))"
                + " OR (channel='WECHAT' AND state IN ('UNCONFIRMED','ACCEPTED','DELIVERED','FAILED'))");
        replaceCheck(connection, schema, "notification_delivery_receipt_observations", List.of("channel", "'sms'", "'voice'"), List.of("state", "source"), "notification_delivery_receipt_observations_channel_check",
            "channel IN ('SMS','VOICE','WECHAT')");
        replaceCheck(connection, schema, "notification_delivery_receipt_observations", List.of("channel", "'sms'", "'voice'", "state"), List.of("source"), "notification_delivery_receipt_observations_state_check",
            "(channel='SMS' AND state IN ('UNCONFIRMED','WAITING','DELIVERED','FAILED'))"
                + " OR (channel='VOICE' AND state IN ('UNCONFIRMED','WAITING','HEARD_FULL','CONNECTED_PARTIAL','NOT_CONNECTED'))"
                + " OR (channel='WECHAT' AND state IN ('UNCONFIRMED','ACCEPTED','DELIVERED','FAILED'))");
        replaceCheck(connection, schema, "notification_delivery_receipt_observations", List.of("source", "'manual'", "'scheduled'"), List.of(), "notification_delivery_receipt_observations_source_check",
            "source IN ('MANUAL','SCHEDULED','SEND_RESULT','PROVIDER_CALLBACK')");

        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE notification_rules ADD COLUMN wechat_escalation_interval_seconds INTEGER CHECK(wechat_escalation_interval_seconds BETWEEN 0 AND 604800)");
            statement.execute("ALTER TABLE notification_contacts ADD COLUMN wechat_binding_id VARCHAR(64)");
            statement.execute("ALTER TABLE notification_channel_policies ADD COLUMN wechat_enabled BOOLEAN");
            statement.execute("ALTER TABLE notification_provider_attempts ADD COLUMN binding_id VARCHAR(64)");
            statement.execute("ALTER TABLE notification_provider_attempts ADD COLUMN provider_config_revision BIGINT");
            statement.execute("ALTER TABLE notification_provider_attempts ADD COLUMN template_id VARCHAR(128)");
            statement.execute("ALTER TABLE notification_provider_attempts ADD COLUMN payload_json VARCHAR(4096)");
            statement.execute("ALTER TABLE notification_provider_attempts ADD COLUMN payload_digest VARCHAR(64)");
        }

        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE notification_wechat_config ("
                + " id INTEGER PRIMARY KEY CHECK(id=1),"
                + " enabled BOOLEAN NOT NULL DEFAULT FALSE,"
                + " app_id VARCHAR(64),"
                + " app_secret_cipher TEXT, app_secret_nonce VARCHAR(64), app_secret_masked VARCHAR(64),"
                + " callback_token_cipher TEXT, callback_token_nonce VARCHAR(64), callback_token_masked VARCHAR(64),"
                + " encoding_aes_key_cipher TEXT, encoding_aes_key_nonce VARCHAR(64), encoding_aes_key_masked VARCHAR(64),"
                + " oauth_redirect_uri VARCHAR(500), callback_url VARCHAR(500),"
                + " fire_template_id VARCHAR(128), fault_template_id VARCHAR(128),"
                + " fire_device_key VARCHAR(32), fire_address_key VARCHAR(32), fire_time_key VARCHAR(32), fire_event_key VARCHAR(32),"
                + " fault_device_key VARCHAR(32), fault_address_key VARCHAR(32), fault_time_key VARCHAR(32), fault_event_key VARCHAR(32),"
                + " credentials_revision BIGINT NOT NULL DEFAULT 0 CHECK(credentials_revision>=0),"
                + " revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),"
                + " updated_at TIMESTAMP WITH TIME ZONE, updated_by VARCHAR(64))");
            statement.execute("INSERT INTO notification_wechat_config(id,enabled,fire_device_key,fire_address_key,fire_time_key,fire_event_key,"
                + "fault_device_key,fault_address_key,fault_time_key,fault_event_key,credentials_revision,revision,updated_at) VALUES"
                + " (1,FALSE,'thing1','thing3','time2','const4','thing1','thing3','time2','const4',0,0,CURRENT_TIMESTAMP)");

            statement.execute("CREATE TABLE notification_wechat_credentials_history ("
                + " credentials_revision BIGINT PRIMARY KEY,"
                + " app_id VARCHAR(64) NOT NULL,"
                + " app_secret_cipher TEXT, app_secret_nonce VARCHAR(64),"
                + " callback_token_cipher TEXT, callback_token_nonce VARCHAR(64),"
                + " encoding_aes_key_cipher TEXT, encoding_aes_key_nonce VARCHAR(64),"
                + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, created_by VARCHAR(64))");

            statement.execute("CREATE TABLE notification_wechat_bindings ("
                + " id VARCHAR(64) PRIMARY KEY,"
                + " tenant_id VARCHAR(64),"
                + " user_id VARCHAR(64) NOT NULL,"
                + " app_id VARCHAR(64) NOT NULL,"
                + " openid_cipher TEXT NOT NULL, openid_nonce VARCHAR(64) NOT NULL,"
                + " openid_masked VARCHAR(64) NOT NULL, openid_fingerprint VARCHAR(64) NOT NULL,"
                + " unionid_cipher TEXT, unionid_nonce VARCHAR(64),"
                + " status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),"
                + " active_slot VARCHAR(8) CHECK(active_slot IS NULL OR active_slot='ACTIVE'),"
                + " follow_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK(follow_state IN ('UNKNOWN','SUBSCRIBED','UNSUBSCRIBED')),"
                + " revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),"
                + " bound_at TIMESTAMP WITH TIME ZONE NOT NULL, last_seen_at TIMESTAMP WITH TIME ZONE, revoked_at TIMESTAMP WITH TIME ZONE, revoked_reason VARCHAR(64),"
                + " UNIQUE(id,tenant_id),"
                + " UNIQUE(user_id,app_id,active_slot),"
                + " UNIQUE(app_id,openid_fingerprint,active_slot),"
                + " CHECK((status='ACTIVE' AND active_slot='ACTIVE' AND revoked_at IS NULL) OR (status='REVOKED' AND active_slot IS NULL AND revoked_at IS NOT NULL)),"
                + " FOREIGN KEY(user_id) REFERENCES app_users(id),"
                + " FOREIGN KEY(tenant_id) REFERENCES tenants(id))");
            statement.execute("CREATE INDEX idx_wechat_binding_tenant ON notification_wechat_bindings(tenant_id,status,app_id)");
            statement.execute("CREATE INDEX idx_wechat_binding_user ON notification_wechat_bindings(user_id,app_id,status)");

            statement.execute("CREATE TABLE notification_wechat_binding_history ("
                + " id VARCHAR(64) PRIMARY KEY,"
                + " binding_id VARCHAR(64) NOT NULL REFERENCES notification_wechat_bindings(id),"
                + " tenant_id VARCHAR(64), user_id VARCHAR(64) NOT NULL,"
                + " event VARCHAR(16) NOT NULL CHECK(event IN ('BOUND','REPLACED','UNBOUND')),"
                + " masked_openid VARCHAR(64) NOT NULL, actor_id VARCHAR(64), detail_code VARCHAR(64),"
                + " created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
            statement.execute("CREATE INDEX idx_wechat_binding_history_user ON notification_wechat_binding_history(user_id,created_at DESC)");

            statement.execute("CREATE TABLE notification_wechat_oauth_states ("
                + " id VARCHAR(64) PRIMARY KEY,"
                + " state_hash VARCHAR(64) NOT NULL UNIQUE,"
                + " audience VARCHAR(16) NOT NULL CHECK(audience IN ('management','customer')),"
                + " user_id VARCHAR(64) NOT NULL, tenant_id VARCHAR(64),"
                + " session_token_hash VARCHAR(64) NOT NULL, account_revision BIGINT NOT NULL,"
                + " app_id VARCHAR(64) NOT NULL, app_fingerprint VARCHAR(64) NOT NULL,"
                + " config_revision BIGINT NOT NULL, credentials_revision BIGINT NOT NULL,"
                + " status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','CALLBACK_RECEIVED','CONFIRMED','REJECTED','EXPIRED')),"
                + " revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),"
                + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                + " callback_at TIMESTAMP WITH TIME ZONE, finished_at TIMESTAMP WITH TIME ZONE,"
                + " openid_cipher TEXT, openid_nonce VARCHAR(64), openid_masked VARCHAR(64), openid_fingerprint VARCHAR(64),"
                + " unionid_cipher TEXT, unionid_nonce VARCHAR(64),"
                + " is_snapshot_user BOOLEAN, failure_code VARCHAR(64),"
                + " CHECK((status IN ('PENDING','EXPIRED') AND openid_cipher IS NULL) OR status='REJECTED' OR (status IN ('CALLBACK_RECEIVED','CONFIRMED') AND openid_cipher IS NOT NULL)))");
            statement.execute("CREATE INDEX idx_wechat_oauth_user ON notification_wechat_oauth_states(user_id,created_at DESC)");
            statement.execute("CREATE INDEX idx_wechat_oauth_expiry ON notification_wechat_oauth_states(status,expires_at)");

            statement.execute("CREATE TABLE notification_wechat_callback_events ("
                + " id VARCHAR(64) PRIMARY KEY,"
                + " event_key VARCHAR(64) NOT NULL UNIQUE,"
                + " app_id VARCHAR(64), signature_mode VARCHAR(16) NOT NULL,"
                + " msg_type VARCHAR(32), event VARCHAR(64), msg_id VARCHAR(128),"
                + " from_openid_masked VARCHAR(64), provider_status VARCHAR(64),"
                + " received_at TIMESTAMP WITH TIME ZONE NOT NULL)");

            statement.execute("ALTER TABLE notification_contacts ADD CONSTRAINT notification_contacts_wechat_binding_fk"
                + " FOREIGN KEY(wechat_binding_id,tenant_id) REFERENCES notification_wechat_bindings(id,tenant_id)");
            statement.execute("ALTER TABLE notification_provider_attempts ADD CONSTRAINT notification_attempt_wechat_binding_fk"
                + " FOREIGN KEY(binding_id) REFERENCES notification_wechat_bindings(id)");
            statement.execute("CREATE INDEX idx_notification_attempt_wechat_receipt ON notification_provider_attempts(channel,provider_id,status)");
        }
    }

    /** Replaces exactly one expression-matched check; ambiguous or absent lookups refuse the migration. */
    private static void replaceCheck(Connection connection, String schema, String table, List<String> include, List<String> exclude,
                                     String newName, String newExpression) throws Exception {
        Check check = findCheck(connection, schema, table, include, exclude);
        if (check == null) throw new IllegalStateException("Expected check on " + table + " " + include + " was not found");
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + quote(check.name()));
            statement.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + newName + " CHECK (" + newExpression + ")");
        }
    }

    private static Check findCheck(Connection connection, String schema, String table, List<String> include, List<String> exclude) throws Exception {
        List<Check> matches = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc "
                + "JOIN information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog "
                + "AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name "
                + "WHERE LOWER(tc.table_name)=? AND tc.table_schema=? AND tc.constraint_type='CHECK'")) {
            query.setString(1, table.toLowerCase(Locale.ROOT));
            query.setString(2, schema);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String expression = rows.getString(2);
                    if (expression == null) continue;
                    String lowered = expression.toLowerCase(Locale.ROOT);
                    boolean includesAll = include.stream().allMatch(lowered::contains);
                    boolean excludesNone = exclude.stream().noneMatch(lowered::contains);
                    if (includesAll && excludesNone) matches.add(new Check(rows.getString(1), expression));
                }
            }
        }
        if (matches.isEmpty()) return null;
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous check on " + table + " for " + include + ": " + matches.size());
        return matches.getFirst();
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
