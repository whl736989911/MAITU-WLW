package com.fireplatform.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "IOT_MIGRATION_PG_URL", matches = ".+")
class H2PostgresCutoverIntegrationTest {
    @TempDir Path directory;

    private static final OffsetDateTime OBSERVED = OffsetDateTime.parse("2026-11-11T00:21:33.987654+05:30");
    private static final String RAW = "{ \"原文\": \"🚨\", \"n\": 1.00 }\n";

    @Test
    void dryRunAndCutoverPreservePhysicalUnknownAndHistoricalOwnership() throws Exception {
        withTarget(target -> {
            Source source = createSource(false);
            JsonNode plan = run(source, target, directory.resolve("plan.json"), false, 0);
            assertThat(plan.path("status").asText()).isEqualTo("DRY_RUN");
            assertFingerprint(source, plan);
            assertNoTargetTables(target);

            JsonNode evidence = run(source, target, directory.resolve("cutover.json"), true, 0);
            assertThat(evidence.path("committed").booleanValue()).isTrue();
            assertThat(evidence.path("foreignKeys").path("validated").booleanValue()).isTrue();
            assertThat(evidence.path("flywayHistory").path("validatedAfterCommit").booleanValue()).isTrue();
            assertFingerprint(source, evidence);

            try (var connection = target.connect(); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT id,enabled,must_change_password FROM app_users ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("id")).isEqualTo("cutover-user-false");
                    assertThat(rows.getBoolean("enabled")).isFalse();
                    assertThat(rows.getBoolean("must_change_password")).isTrue();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("id")).isEqualTo("cutover-user-true");
                    assertThat(rows.getBoolean("enabled")).isTrue();
                    assertThat(rows.getBoolean("must_change_password")).isFalse();
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = statement.executeQuery("""
                    SELECT d.tenant_id,d.physical_state,d.last_message_at,d.battery_percent,d.address,
                           t.target_tenant_id, h.tenant_id AS history_tenant,h.from_place_name,
                           b.state AS binding_state,b.generation,c.state AS command_state,c.finished_at,
                           a.outcome,a.actor_id,a.request_id,m.payload_raw,m.observed_at,m.received_at
                    FROM devices d
                    JOIN device_transfers t ON t.source_device_id=d.id AND t.source_tenant_id=d.tenant_id
                    JOIN device_place_history h ON h.device_id=d.id AND h.tenant_id=d.tenant_id
                    JOIN device_source_bindings b ON b.device_id=d.id AND b.tenant_id=d.tenant_id
                    JOIN device_service_commands c ON c.device_id=d.id AND c.tenant_id=d.tenant_id
                    JOIN audit_log a ON a.target_id=c.id AND a.tenant_id=c.tenant_id
                    JOIN legacy_ingest_messages m ON m.device_id=d.id AND m.tenant_id=d.tenant_id
                    WHERE d.id='cutover-device'
                    """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("tenant_id")).isEqualTo("cutover-unit");
                    assertThat(rows.getString("target_tenant_id")).isEqualTo("new-unit");
                    assertThat(rows.getString("physical_state")).isEqualTo("UNKNOWN");
                    assertThat(rows.getObject("last_message_at", OffsetDateTime.class).toInstant()).isEqualTo(OBSERVED.toInstant());
                    assertThat(rows.getObject("battery_percent")).isNull();
                    assertThat(rows.getString("address")).isEqualTo("保留原文🚨");
                    assertThat(rows.getString("history_tenant")).isEqualTo("cutover-unit");
                    assertThat(rows.getString("from_place_name")).isEqualTo("已移除旧楼层");
                    assertThat(rows.getString("binding_state")).isEqualTo("RELEASED");
                    assertThat(rows.getLong("generation")).isEqualTo(3);
                    assertThat(rows.getString("command_state")).isEqualTo("UNKNOWN");
                    assertThat(rows.getObject("finished_at")).isNull();
                    assertThat(rows.getString("outcome")).isEqualTo("UNKNOWN");
                    assertThat(rows.getString("actor_id")).isEqualTo("cutover-user-false");
                    assertThat(rows.getString("request_id")).isEqualTo("legacy-receipt-1");
                    assertThat(rows.getString("payload_raw")).isEqualTo(RAW);
                    assertThat(rows.getObject("observed_at")).isNull();
                    assertThat(rows.getObject("received_at", OffsetDateTime.class).toInstant()).isEqualTo(OBSERVED.toInstant());
                    assertThat(rows.next()).isFalse();
                }
            }
        });
    }

    @Test
    void foreignKeyFailureRollsBackCopiedDataAndLeavesTargetSchemaProtected() throws Exception {
        withTarget(target -> {
            Source source = createSource(true);
            JsonNode evidence = run(source, target, directory.resolve("failed.json"), true, 1);
            assertThat(evidence.path("commitOutcome").asText()).isEqualTo("NOT_ATTEMPTED");
            assertThat(evidence.path("committed").booleanValue()).isFalse();
            assertThat(evidence.path("rollback").path("attempted").booleanValue()).isTrue();
            assertThat(evidence.path("rollback").path("succeeded").booleanValue()).isTrue();
            assertThat(evidence.path("error").path("message").asText()).contains("SQLState=23503");
            assertFingerprint(source, evidence);
            try (var connection = target.connect(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                     SELECT (SELECT count(*) FROM tenants WHERE id='cutover-unit'),
                            (SELECT count(*) FROM audit_log WHERE id='cutover-audit'),
                            (SELECT setting_value FROM numeric_settings WHERE setting_key='default_page_size'),
                            (SELECT count(*) FROM flyway_schema_history WHERE installed_rank=-1),
                            (SELECT count(*) FROM pg_constraint
                             WHERE conrelid='user_permissions'::regclass AND contype='f' AND convalidated)
                     """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
                assertThat(rows.getLong(2)).isZero();
                assertThat(rows.getLong(3)).isEqualTo(20);
                assertThat(rows.getLong(4)).isZero();
                assertThat(rows.getLong(5)).isEqualTo(1);
            }
            JsonNode retry = run(source, target, directory.resolve("retry.json"), true, 1);
            assertThat(retry.path("error").path("message").asText()).contains("target public schema is not empty");
        });
    }

    @Test
    void protectsSourceWhenRequestedManifestIsTheH2File() throws Exception {
        withTarget(target -> {
            Source source = createSource(false);
            // Even a failure receipt must not replace the source file.
            assertThat(runCli(source, target, source.file(), true, 2)).contains("manifest must not refer to the H2 source");
            Path alias = directory.resolve("alias.json");
            Files.createLink(alias, source.file());
            assertThat(runCli(source, target, alias, true, 2)).contains("manifest must not refer to the H2 source");
            assertThat(Files.isSameFile(alias, source.file())).isTrue();
            assertNoTargetTables(target);
        });
    }

    @Test
    void manifestWriteFailureDoesNotClaimThatCommittedDataWasRolledBack() throws Exception {
        withTarget(target -> {
            Source source = createSource(false);
            Path destination = Files.createDirectory(directory.resolve("blocked.json"));
            Path kept = Files.writeString(destination.resolve("retained"), "not an output file");
            assertThat(runCli(source, target, destination, true, 1))
                .contains("manifest could not be written (target commit=COMMITTED)");
            assertThat(Files.readString(kept)).isEqualTo("not an output file");
            try (var connection = target.connect(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT physical_state FROM devices WHERE id='cutover-device'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("UNKNOWN");
            }
        });
    }

    private Source createSource(boolean orphan) throws Exception {
        String password = UUID.randomUUID().toString();
        Path sourcePath = directory.resolve("source");
        String url = "jdbc:h2:file:" + sourcePath + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        Flyway flyway = Flyway.configure().dataSource(url, "sa", password).load();
        flyway.migrate();
        String version = flyway.info().current().getVersion().getVersion();
        try (var source = DriverManager.getConnection(url, "sa", password);
             var statements = source.createStatement()) {
            statements.executeUpdate("INSERT INTO tenants(id,name) VALUES ('cutover-unit','迁移校验🚒'),('new-unit','承接合成单位')");
            statements.executeUpdate("UPDATE numeric_settings SET setting_value=77 WHERE setting_key='default_page_size'");
            try (var statement = source.prepareStatement("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) VALUES (?,?,?,'PLATFORM','not-a-login-hash',?,?)")) {
                for (boolean enabled : List.of(false, true)) {
                    statement.setString(1, "cutover-user-" + enabled);
                    statement.setString(2, "cutover-user-" + enabled);
                    statement.setString(3, "只用于隔离迁移校验");
                    statement.setBoolean(4, !enabled);
                    statement.setBoolean(5, enabled);
                    statement.executeUpdate();
                }
            }
            try (var statement = source.prepareStatement("""
                INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state,last_message_at,battery_percent)
                VALUES ('cutover-device','cutover-unit','隔离迁移校验','cutover-eui','fixture','保留原文🚨','UNKNOWN',FALSE,'LEGACY_UNVERIFIED',?,NULL)
                """)) {
                statement.setObject(1, OBSERVED);
                statement.executeUpdate();
            }
            seedHistory(source);
            if (orphan) {
                statements.execute("SET REFERENTIAL_INTEGRITY FALSE");
                statements.executeUpdate("INSERT INTO user_permissions(user_id,permission) VALUES ('missing-actor','fixture')");
            }
            statements.execute("SHUTDOWN");
        }
        Path file = directory.resolve("source.mv.db");
        return new Source(url + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;LAZY_QUERY_EXECUTION=TRUE",
            password, version, file, digest(file));
    }

    private static void seedHistory(Connection source) throws Exception {
        try (var statements = source.createStatement()) {
            statements.executeUpdate("""
                INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state)
                VALUES ('new-device','new-unit','合成承接设备','new-eui','fixture','合成地址','UNKNOWN')
                """);
            statements.executeUpdate("""
                INSERT INTO device_transfers(id,source_device_id,source_tenant_id,target_device_id,target_tenant_id,actor_id,reason,occurred_at)
                VALUES ('cutover-transfer','cutover-device','cutover-unit','new-device','new-unit','cutover-user-false','历史保留',CURRENT_TIMESTAMP)
                """);
            statements.executeUpdate("""
                INSERT INTO device_place_history(id,device_id,tenant_id,action,from_place_id,from_place_name,to_place_id,to_place_name,
                    revision_before,revision_after,actor_id,occurred_at)
                VALUES ('cutover-history','cutover-device','cutover-unit','MOVE','old-place','已移除旧楼层',
                    'former-place','旧单位库房',0,1,'cutover-user-false',CURRENT_TIMESTAMP)
                """);
            statements.executeUpdate("""
                INSERT INTO device_source_bindings(id,device_id,tenant_id,driver_key,source_external_id,generation,state,
                    bound_at,bound_by,released_at,released_by,release_reason)
                VALUES ('cutover-binding','cutover-device','cutover-unit','HIKVISION_FIRECLOUD','fixture-external',3,'RELEASED',
                    CURRENT_TIMESTAMP,'cutover-user-false',CURRENT_TIMESTAMP,'cutover-user-false','合成解绑')
                """);
            statements.executeUpdate("""
                INSERT INTO device_service_commands(id,tenant_id,device_id,driver_key,driver_mode,service_key,risk_level,result_kind,
                    state,arguments_json,payload_sha256,snapshot_json,actor_id,reason,idem_key,request_hash,created_at,updated_at)
                VALUES ('cutover-command','cutover-unit','cutover-device','HIKVISION_FIRECLOUD','VENDOR_PLATFORM','fixture',
                    'NORMAL','COMMAND','UNKNOWN','{}','placeholder','{}','cutover-user-false','合成未知命令',
                    'fixture-idem','fixture-hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
            statements.executeUpdate("""
                INSERT INTO audit_log(id,actor_id,tenant_id,action,target_id,outcome,request_id,occurred_at)
                VALUES ('cutover-audit','cutover-user-false','cutover-unit','fixture','cutover-command','UNKNOWN','legacy-receipt-1',CURRENT_TIMESTAMP)
                """);
        }
        try (var statement = source.prepareStatement("""
            INSERT INTO legacy_ingest_messages(id,tenant_id,ingress,vendor_platform,external_key,device_id,payload_raw,
                payload_sha256,payload_bytes,received_at,observed_at,state)
            VALUES ('cutover-raw','cutover-unit','HIKVISION_PUSH','HIKVISION','fixture-message','cutover-device',?,?,?,?,
                NULL,'PENDING_DECODER')
            """)) {
            byte[] bytes = RAW.getBytes(StandardCharsets.UTF_8);
            statement.setString(1, RAW);
            statement.setString(2, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            statement.setLong(3, bytes.length);
            statement.setObject(4, OBSERVED);
            statement.executeUpdate();
        }
    }

    private JsonNode run(Source source, Target target, Path manifest, boolean execute, int expectedExit) throws Exception {
        runCli(source, target, manifest, execute, expectedExit);
        String receipt = Files.readString(manifest);
        assertThat(receipt).doesNotContain(source.file().getParent().toString(), source.password(), target.password());
        return new ObjectMapper().readTree(receipt);
    }

    private String runCli(Source source, Target target, Path manifest, boolean execute, int expectedExit) throws Exception {
        Path output = directory.resolve(manifest.getFileName() + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, H2PostgresCutover.class.getName(), "--expected-version", source.version(),
            "--manifest-out", manifest.toString()));
        if (execute) command.add("--execute");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        var environment = process.environment();
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("_JAVA_OPTIONS");
        environment.put("FIRE_CUTOVER_SOURCE_URL", source.url());
        environment.put("FIRE_CUTOVER_SOURCE_USERNAME", "sa");
        environment.put("FIRE_CUTOVER_SOURCE_PASSWORD", source.password());
        environment.put("FIRE_CUTOVER_TARGET_URL", target.url());
        environment.put("FIRE_CUTOVER_TARGET_USERNAME", target.username());
        environment.put("FIRE_CUTOVER_TARGET_PASSWORD", target.password());
        var child = process.start();
        try {
            assertThat(child.waitFor(120, TimeUnit.SECONDS)).as("Cutover CLI fixture deadline").isTrue();
            assertThat(digest(source.file())).as("The H2 source remains byte-for-byte unchanged").containsExactly(source.digest());
            String console = Files.readString(output);
            assertThat(child.exitValue()).withFailMessage("Unexpected cutover exit: %s", console).isEqualTo(expectedExit);
            return console;
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor();
        }
    }

    private static void assertFingerprint(Source source, JsonNode evidence) {
        String sha = HexFormat.of().formatHex(source.digest());
        assertThat(evidence.path("source").path("sha256Before").asText()).isEqualTo(sha);
        assertThat(evidence.path("source").path("sha256After").asText()).isEqualTo(sha);
        assertThat(evidence.path("source").path("fingerprintMatches").booleanValue()).isTrue();
    }

    private static void assertNoTargetTables(Target target) throws Exception {
        try (var connection = target.connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public'")) {
            assertThat(rows.next()).isFalse();
        }
    }

    private void withTarget(TargetTest test) throws Exception {
        String baseUrl = System.getenv("IOT_MIGRATION_PG_URL");
        URI base = URI.create(baseUrl.substring("jdbc:".length()));
        assertThat(base.getHost()).as("Only the owned loopback PostgreSQL fixture is permitted").isEqualTo("127.0.0.1");
        String username = System.getenv("IOT_MIGRATION_PG_USER");
        String password = System.getenv("IOT_MIGRATION_PG_PASSWORD");
        String database = "cutover_regression_" + UUID.randomUUID().toString().replace("-", "");
        String targetUrl = "jdbc:" + new URI(base.getScheme(), null, base.getHost(), base.getPort(), "/" + database, base.getQuery(), null);
        try (var administrator = DriverManager.getConnection(baseUrl, username, password);
             var statement = administrator.createStatement()) {
            statement.execute("CREATE DATABASE \"" + database + "\"");
            try {
                statement.execute("ALTER DATABASE \"" + database + "\" SET timezone TO 'America/Los_Angeles'");
                test.run(new Target(targetUrl, username, password));
            } finally {
                statement.execute("DROP DATABASE \"" + database + "\" WITH (FORCE)");
            }
        }
    }

    @FunctionalInterface
    private interface TargetTest {
        void run(Target target) throws Exception;
    }

    private record Target(String url, String username, String password) {
        Connection connect() throws Exception {
            return DriverManager.getConnection(url, username, password);
        }
    }

    private record Source(String url, String password, String version, Path file, byte[] digest) {
    }

    private static byte[] digest(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
