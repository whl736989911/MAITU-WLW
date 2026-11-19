package com.fireplatform.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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

    @Test
    void actualPostgresBooleanMetadataAndOffsetTimestampsSurviveCutover() throws Exception {
        String baseUrl = System.getenv("IOT_MIGRATION_PG_URL");
        URI base = URI.create(baseUrl.substring("jdbc:".length()));
        assertThat(base.getHost()).as("Only the owned loopback PostgreSQL fixture is permitted").isEqualTo("127.0.0.1");
        String username = System.getenv("IOT_MIGRATION_PG_USER");
        String password = System.getenv("IOT_MIGRATION_PG_PASSWORD");
        String database = "cutover_regression_" + UUID.randomUUID().toString().replace("-", "");
        String targetUrl = "jdbc:" + new URI(base.getScheme(), null, base.getHost(), base.getPort(), "/" + database, base.getQuery(), null);
        try (var administrator = DriverManager.getConnection(baseUrl, username, password)) {
            try (var statement = administrator.createStatement()) {
                statement.execute("CREATE DATABASE \"" + database + "\"");
            }
            try {
                String sourcePassword = UUID.randomUUID().toString();
                Path sourcePath = directory.resolve("source");
                String sourceUrl = "jdbc:h2:file:" + sourcePath + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
                Flyway flyway = Flyway.configure().dataSource(sourceUrl, "sa", sourcePassword).load();
                flyway.migrate();
                String version = flyway.info().current().getVersion().getVersion();
                OffsetDateTime observed = OffsetDateTime.parse("2026-11-11T18:21:33.987654+05:30");
                try (var source = DriverManager.getConnection(sourceUrl, "sa", sourcePassword)) {
                    try (var statement = source.prepareStatement("INSERT INTO tenants(id,name) VALUES ('cutover-unit',?)")) {
                        statement.setString(1, "迁移校验🚒");
                        statement.executeUpdate();
                    }
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
                    try (var statement = source.prepareStatement("INSERT INTO devices(id,tenant_id,name,device_eui,model,address,physical_state,is_simulated,integration_state,last_message_at,battery_percent) VALUES ('cutover-device','cutover-unit','隔离迁移校验','cutover-eui','fixture','保留原文🚨','UNKNOWN',FALSE,'LEGACY_UNVERIFIED',?,NULL)")) {
                        statement.setObject(1, observed);
                        statement.executeUpdate();
                    }
                    // The standalone cutover accepts only an offline source, not a database
                    // whose lifetime is still owned by the fixture JVM.
                    try (var statement = source.createStatement()) {
                        statement.execute("SHUTDOWN");
                    }
                }
                Path sourceFile = directory.resolve("source.mv.db");
                byte[] sourceDigest = digest(sourceFile);
                Path manifest = directory.resolve("cutover.json");
                Path output = directory.resolve("cutover.log");
                String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, H2PostgresCutover.class.getName(), "--execute", "--expected-version", version,
                    "--manifest-out", manifest.toString()).redirectErrorStream(true).redirectOutput(output.toFile());
                var environment = process.environment();
                environment.remove("JAVA_TOOL_OPTIONS");
                environment.remove("_JAVA_OPTIONS");
                environment.put("FIRE_CUTOVER_SOURCE_URL", sourceUrl + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;LAZY_QUERY_EXECUTION=TRUE");
                environment.put("FIRE_CUTOVER_SOURCE_USERNAME", "sa");
                environment.put("FIRE_CUTOVER_SOURCE_PASSWORD", sourcePassword);
                environment.put("FIRE_CUTOVER_TARGET_URL", targetUrl);
                environment.put("FIRE_CUTOVER_TARGET_USERNAME", username);
                environment.put("FIRE_CUTOVER_TARGET_PASSWORD", password);
                var child = process.start();
                try {
                    assertThat(child.waitFor(120, TimeUnit.SECONDS))
                        .withFailMessage("Cutover CLI exceeded its fixture deadline. Output: %s", Files.readString(output)).isTrue();
                    assertThat(child.exitValue()).withFailMessage("Cutover CLI failed: %s", Files.readString(output)).isZero();
                } finally {
                    if (child.isAlive()) child.destroyForcibly().waitFor();
                }
                assertThat(digest(sourceFile)).as("The H2 source remains byte-for-byte unchanged").containsExactly(sourceDigest);
                var evidence = new ObjectMapper().readTree(Files.readString(manifest));
                assertThat(evidence.path("committed").booleanValue()).isTrue();
                assertThat(evidence.path("foreignKeys").path("validated").booleanValue()).isTrue();
                try (var target = DriverManager.getConnection(targetUrl, username, password)) {
                    try (var statement = target.createStatement(); var result = statement.executeQuery("SELECT id,enabled,must_change_password FROM app_users ORDER BY id")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString("id")).isEqualTo("cutover-user-false");
                        assertThat(result.getBoolean("enabled")).isFalse();
                        assertThat(result.getBoolean("must_change_password")).isTrue();
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString("id")).isEqualTo("cutover-user-true");
                        assertThat(result.getBoolean("enabled")).isTrue();
                        assertThat(result.getBoolean("must_change_password")).isFalse();
                        assertThat(result.next()).isFalse();
                    }
                    try (var statement = target.createStatement(); var result = statement.executeQuery("SELECT last_message_at,battery_percent,address FROM devices WHERE id='cutover-device'")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getObject("last_message_at", OffsetDateTime.class).toInstant()).isEqualTo(observed.toInstant());
                        assertThat(result.getObject("battery_percent")).isNull();
                        assertThat(result.getString("address")).isEqualTo("保留原文🚨");
                    }
                }
            } finally {
                try (var statement = administrator.createStatement()) {
                    statement.execute("DROP DATABASE \"" + database + "\" WITH (FORCE)");
                }
            }
        }
    }

    private static byte[] digest(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
