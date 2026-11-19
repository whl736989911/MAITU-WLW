package com.fireplatform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class NotificationMigrationTest {
    @Test void v9AddsOnlySimulationSchemaAndRootPermissionsWithoutInventingContactsRulesOrBusinessDefaults(){String url="jdbc:h2:mem:notification-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";Flyway.configure().dataSource(url,"sa","").target("8").load().migrate();var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));jdbc.update("INSERT INTO tenants(id,name) VALUES ('old-unit','Old unit')");for(String id:new String[]{"bootstrap-admin","protected-other","ordinary"})jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled,is_protected) VALUES (?,?,?,'PLATFORM','hash',FALSE,TRUE,?)",id,id,id,!id.equals("ordinary"));jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,tenant_id,password_hash,must_change_password,enabled,is_protected) VALUES ('old-customer','old-customer','Customer','CUSTOMER','old-unit','hash',FALSE,TRUE,FALSE)");var users=jdbc.queryForList("SELECT * FROM app_users ORDER BY id");var settings=jdbc.queryForList("SELECT * FROM numeric_settings ORDER BY setting_key");Flyway.configure().dataSource(url,"sa","").target("9").load().migrate();assertThat(jdbc.queryForList("SELECT permission FROM user_permissions WHERE user_id='bootstrap-admin'",String.class)).containsExactlyInAnyOrder("notification-config:read","notification-config:write","notification-templates:read","notification-templates:write","notifications:read","notifications:simulate");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id<>'bootstrap-admin'",Long.class)).isZero();for(String table:new String[]{"notification_contacts","notification_templates","notification_rules","notification_rule_receivers","notification_dispatches","notification_records","notification_attempts","notification_history"})assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT fingerprint FROM notification_contact_key_guard WHERE id=1",String.class)).isNull();assertThat(jdbc.queryForList("SELECT * FROM app_users ORDER BY id")).isEqualTo(users);assertThat(jdbc.queryForList("SELECT * FROM numeric_settings ORDER BY setting_key")).isEqualTo(settings);Flyway.configure().dataSource(url,"sa","").target("9").load().migrate();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions",Long.class)).isEqualTo(6);}
    @Test void occurrenceMigrationPreservesOldAttemptsAndSeparatesReminderCycles() {
        String url="jdbc:h2:mem:notification-occurrence-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("45").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        jdbc.update("INSERT INTO notification_provider_attempts(id,tenant_id,alarm_id,device_id,contact_id,channel,attempt_no,status,provider_code,provider_id,created_at,finished_at) VALUES ('old-attempt','unit','alarm','device','contact','SMS',1,'SENT','OK','old-receipt',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var before=jdbc.queryForMap("SELECT * FROM notification_provider_attempts WHERE id='old-attempt'");
        Flyway.configure().dataSource(url,"sa","").target("46").load().migrate();
        var after=jdbc.queryForMap("SELECT * FROM notification_provider_attempts WHERE id='old-attempt'");
        assertThat(after.remove("occurrence_key")).isEqualTo("INITIAL");
        assertThat(after).isEqualTo(before);
        jdbc.update("INSERT INTO notification_provider_attempts(id,tenant_id,alarm_id,device_id,contact_id,channel,attempt_no,occurrence_key,status,created_at) VALUES ('cycle-attempt','unit','alarm','device','contact','SMS',1,'REMINDER:1','PENDING',CURRENT_TIMESTAMP)");
        assertThatThrownBy(()->jdbc.update("INSERT INTO notification_provider_attempts(id,tenant_id,alarm_id,device_id,contact_id,channel,attempt_no,occurrence_key,status,created_at) VALUES ('duplicate-cycle','unit','alarm','device','contact','SMS',1,'REMINDER:1','PENDING',CURRENT_TIMESTAMP)")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("SELECT occurrence_key FROM notification_provider_attempts ORDER BY occurrence_key",String.class)).containsExactly("INITIAL","REMINDER:1");
    }
    @Test void v73KeepsOldAttemptsUnprovableAndAddsAppendOnlyReceiptEvidence(){
        String url="jdbc:h2:mem:notification-v73-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("67").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('unit','Unit')");
        jdbc.update("INSERT INTO notification_provider_attempts(id,tenant_id,alarm_id,device_id,contact_id,channel,attempt_no,occurrence_key,status,provider_code,provider_id,created_at,finished_at,event_kind,source_kind,source_id) VALUES ('old-sent','unit','alarm','device','contact','SMS',1,'INITIAL','SENT','OK','biz-old',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'FIRE','ALARM','alarm')");
        Flyway.configure().dataSource(url,"sa","").target("73").load().migrate();
        var snapshot=jdbc.queryForMap("SELECT destination_cipher,destination_nonce,destination_masked,destination_mac,account_fingerprint,snapshot_send_date,out_id FROM notification_provider_attempts WHERE id='old-sent'");
        assertThat(snapshot.values()).allMatch(java.util.Objects::isNull);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_receipts",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_receipt_observations",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT periodic_query_enabled FROM notification_delivery_receipt_policy WHERE id=1",Boolean.class)).isFalse();
        jdbc.update("INSERT INTO notification_delivery_receipts(attempt_id,tenant_id,channel,state,reason,revision,first_observed_at,last_observed_at,last_observation_id) VALUES ('old-sent','unit','SMS','UNCONFIRMED','DESTINATION_SNAPSHOT_UNAVAILABLE',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'obs-1')");
        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery_receipts WHERE attempt_id='old-sent'",String.class)).isEqualTo("UNCONFIRMED");
        assertThatThrownBy(()->jdbc.update("UPDATE notification_delivery_receipts SET state='HEARD_FULL' WHERE attempt_id='old-sent'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void v58PreservesLegacyRuleAndTemplateProvenanceWhileAllowingRealRulesWithoutLegacyColumns(){
        String url="jdbc:h2:mem:notification-v58-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";Flyway.configure().dataSource(url,"sa","").target("57").load().migrate();var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        jdbc.update("INSERT INTO tenants(id,name) VALUES ('v58-unit','V58 unit')");jdbc.update("INSERT INTO notification_contacts(id,tenant_id,name,contact_type,phone_cipher,phone_nonce,phone_masked,phone_mac,enabled,created_at) VALUES ('v58-contact','v58-unit','Contact','OTHER','cipher','nonce','138****1234','mac',TRUE,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notification_templates(id,name,channel,mode,body,enabled,created_at) VALUES ('old-sms','Old SMS','SMS','SIMULATION','legacy body',TRUE,CURRENT_TIMESTAMP),('old-voice','Old voice','VOICE','SIMULATION','legacy voice',FALSE,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,mode,sms_template_id,voice_template_id,max_attempts,retry_delay_seconds,escalation_interval_seconds,sms_escalation_interval_seconds,voice_escalation_interval_seconds,created_at) VALUES ('old-rule','v58-unit','Historical rule',TRUE,'FIRE','SIMULATION','old-sms','old-voice',3,45,120,120,240,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel) VALUES ('v58-unit','old-rule','v58-contact',1,'SMS'),('v58-unit','old-rule','v58-contact',1,'VOICE')");
        var oldRule=jdbc.queryForMap("SELECT mode,sms_template_id,voice_template_id,max_attempts,retry_delay_seconds FROM notification_rules WHERE id='old-rule'");var oldTemplates=jdbc.queryForList("SELECT * FROM notification_templates ORDER BY id");
        Flyway.configure().dataSource(url,"sa","").target("58").load().migrate();
        var legacy=jdbc.queryForMap("SELECT legacy_mode,legacy_sms_template_id,legacy_voice_template_id,legacy_max_attempts,legacy_retry_delay_seconds FROM notification_rules WHERE id='old-rule'");assertThat(legacy.get("legacy_mode")).isEqualTo(oldRule.get("mode"));assertThat(legacy.get("legacy_sms_template_id")).isEqualTo(oldRule.get("sms_template_id"));assertThat(legacy.get("legacy_voice_template_id")).isEqualTo(oldRule.get("voice_template_id"));assertThat(legacy.get("legacy_max_attempts")).isEqualTo(oldRule.get("max_attempts"));assertThat(legacy.get("legacy_retry_delay_seconds")).isEqualTo(oldRule.get("retry_delay_seconds"));assertThat(jdbc.queryForList("SELECT * FROM notification_templates ORDER BY id")).isEqualTo(oldTemplates);
        jdbc.update("INSERT INTO notification_rules(id,tenant_id,name,enabled,event_scope,escalation_interval_seconds,sms_escalation_interval_seconds,voice_escalation_interval_seconds,created_at) VALUES ('real-rule','v58-unit','Real rule',TRUE,'FIRE',NULL,0,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notification_rule_receivers(tenant_id,rule_id,contact_id,level,channel) VALUES ('v58-unit','real-rule','v58-contact',1,'SMS'),('v58-unit','real-rule','v58-contact',1,'VOICE')");
        assertThat(jdbc.queryForMap("SELECT legacy_mode,legacy_sms_template_id,legacy_voice_template_id,legacy_max_attempts,legacy_retry_delay_seconds FROM notification_rules WHERE id='real-rule'")).containsEntry("legacy_mode",null).containsEntry("legacy_sms_template_id",null).containsEntry("legacy_voice_template_id",null).containsEntry("legacy_max_attempts",null).containsEntry("legacy_retry_delay_seconds",null);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_templates",Long.class)).isEqualTo(2L);
    }

}
