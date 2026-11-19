package com.fireplatform.ops;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Isolated H2 snapshot to PostgreSQL 17 cutover.
 *
 * <p>This is a deliberately small JDBC/Flyway command line tool: it never starts the Spring
 * application or the application Bootstrap and it only talks to the two JDBC URLs supplied in the
 * environment.  The default action is a read-only plan; all target DDL/DML requires --execute.</p>
 */
public final class H2PostgresCutover {

    private static final String ENV_SOURCE_URL = "FIRE_CUTOVER_SOURCE_URL";
    private static final String ENV_SOURCE_USER = "FIRE_CUTOVER_SOURCE_USERNAME";
    private static final String ENV_SOURCE_PASSWORD = "FIRE_CUTOVER_SOURCE_PASSWORD";
    private static final String ENV_TARGET_URL = "FIRE_CUTOVER_TARGET_URL";
    private static final String ENV_TARGET_USER = "FIRE_CUTOVER_TARGET_USERNAME";
    private static final String ENV_TARGET_PASSWORD = "FIRE_CUTOVER_TARGET_PASSWORD";

    private static final String MIGRATION_LOCATIONS = "classpath:db/migration";
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";
    private static final String SOURCE_FILE_PREFIX = "jdbc:h2:file:";
    private static final String TARGET_URL_PREFIX = "jdbc:postgresql://";
    private static final int FETCH_SIZE = 500;
    private static final int BATCH_SIZE = 200;
    private static final int MAX_REPORTED_MESSAGE = 2000;

    private static final Pattern EMBEDDED_CREDENTIAL = Pattern.compile("(?i)[?&;](user|password)=[^&;]*");
    private static final Pattern URL_USERINFO = Pattern.compile("^jdbc:postgresql://[^/]*@");
    private static final Pattern CURRENT_SCHEMA =
            Pattern.compile("(?i)(?:[?&;])currentschema=([^&;]*)");
    private static final Pattern QUERY_MODE = Pattern.compile("(?i)(?:[?&;])preferQueryMode=([^&;]*)");
    private static final Pattern PASSWORD_SETTING = Pattern.compile("(?i)(password|pwd)\\s*=\\s*[^;&\\s]+");

    private static final ObjectMapper MANIFEST_JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static {
        CANONICAL_JSON.getFactory().configure(
                com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    }

    private H2PostgresCutover() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (UsageException e) {
            System.err.println("H2PostgresCutover: " + e.getMessage());
            printUsage(System.err);
            return 2;
        }
        if (options.help()) {
            printUsage(System.out);
            return 0;
        }

        quietLogging();
        Secrets secrets = null;
        Path sourceFile = null;
        boolean safeToWriteManifest = false;
        int exitCode = 0;
        Report report = new Report(options);
        try {
            secrets = Secrets.load();
            sourceFile = validateSourceUrl(secrets.sourceUrl());
            verifyManifestDestination(sourceFile, options.manifestOut());
            safeToWriteManifest = true;
            validateTargetUrl(secrets.targetUrl());
            new CutoverRun(options, secrets, report, sourceFile).run();
        } catch (Exception failure) {
            String masked = mask(failure, secrets, sourceFile);
            report.fail(failure, masked);
            System.err.println("H2PostgresCutover: FAILED (target commit="
                    + report.commitOutcome + "): " + masked);
            exitCode = failure instanceof UsageException ? 2 : 1;
        }
        if (safeToWriteManifest) {
            try {
                // Recheck so a destination alias created during cutover is not replaced.
                verifyManifestDestination(sourceFile, options.manifestOut());
                report.write(options.manifestOut());
            } catch (Exception failure) {
                System.err.println("H2PostgresCutover: manifest could not be written (target commit="
                        + report.commitOutcome + "): " + mask(failure, secrets, sourceFile));
                return 1;
            }
            if (exitCode == 0) {
                System.out.println(options.execute() ? "H2PostgresCutover: migration committed"
                        : "H2PostgresCutover: dry-run plan OK");
            }
            System.out.println("H2PostgresCutover: private manifest " + options.manifestOut());
        }
        return exitCode;
    }

    /* --------------------------------------------------------------------- */
    /* Command line                                                          */
    /* --------------------------------------------------------------------- */

    private record Options(boolean execute, String expectedVersion, Path manifestOut, boolean help) {

        static Options parse(String[] args) {
            boolean execute = false;
            boolean help = false;
            String expected = null;
            String manifest = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--execute" -> execute = true;
                    case "--help", "-h" -> help = true;
                    case "--expected-version" -> {
                        if (expected != null) {
                            throw new UsageException("--expected-version may be supplied only once");
                        }
                        expected = nextValue(args, ++i, "--expected-version");
                    }
                    case "--manifest-out" -> {
                        if (manifest != null) {
                            throw new UsageException("--manifest-out may be supplied only once");
                        }
                        manifest = nextValue(args, ++i, "--manifest-out");
                    }
                    default -> throw new UsageException("unknown argument: " + arg);
                }
            }
            if (help) {
                return new Options(false, null, null, true);
            }
            if (expected == null) {
                throw new UsageException("--expected-version is required");
            }
            try {
                MigrationVersion.fromVersion(expected);
            } catch (RuntimeException e) {
                throw new UsageException("--expected-version is not a valid Flyway version");
            }
            if (manifest == null || manifest.isBlank()) {
                throw new UsageException("--manifest-out is required");
            }
            return new Options(execute, expected,
                    Paths.get(manifest).toAbsolutePath().normalize(), false);
        }

        private static String nextValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new UsageException(option + " requires a value");
            }
            String value = args[index];
            if (value.isBlank()) {
                throw new UsageException(option + " requires a non-blank value");
            }
            return value;
        }
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("H2PostgresCutover - isolated H2 snapshot -> PostgreSQL 17 cutover");
        out.println();
        out.println("Usage:");
        out.println("  java -Dloader.main=com.fireplatform.ops.H2PostgresCutover \\");
        out.println("       -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher \\");
        out.println("       [--execute] --expected-version <n> --manifest-out <private-path>");
        out.println();
        out.println("Default (no --execute) is a read-only plan. --execute is required for target DDL/DML.");
        out.println();
        out.println("Environment (values are never read from argv and never printed):");
        out.println("  " + ENV_SOURCE_URL + "  existing local jdbc:h2:file URL");
        out.println("      required URL settings: IFEXISTS=TRUE;ACCESS_MODE_DATA=r;LAZY_QUERY_EXECUTION=TRUE");
        out.println("      keep the application's existing settings too:");
        out.println("      MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        out.println("  " + ENV_SOURCE_USER);
        out.println("  " + ENV_SOURCE_PASSWORD);
        out.println("  " + ENV_TARGET_URL + "  jdbc:postgresql:// URL, database must be UTF8");
        out.println("  " + ENV_TARGET_USER);
        out.println("  " + ENV_TARGET_PASSWORD);
        out.println();
        out.println("Safety:");
        out.println("  * the source is opened read-only and is never modified; stop H2 before running");
        out.println("  * the target must be PostgreSQL 17 with a completely empty public schema");
        out.println("  * the private manifest records versions, per-table counts/digests, FK validation");
        out.println("  * committed=true appears only after the target transaction actually commits");
        out.println();
        out.println("Exit codes: 0 success, 1 safety/execution failure, 2 usage/configuration failure.");
    }

    /* --------------------------------------------------------------------- */
    /* Environment and URL safety                                            */
    /* --------------------------------------------------------------------- */

    private record Secrets(String sourceUrl, String sourceUser, String sourcePassword,
                           String targetUrl, String targetUser, String targetPassword) {

        static Secrets load() {
            return new Secrets(requiredEnv(ENV_SOURCE_URL), requiredEnv(ENV_SOURCE_USER),
                    requiredEnv(ENV_SOURCE_PASSWORD), requiredEnv(ENV_TARGET_URL),
                    requiredEnv(ENV_TARGET_USER), requiredEnv(ENV_TARGET_PASSWORD));
        }

        private static String requiredEnv(String name) {
            String value = System.getenv(name);
            if (value == null) {
                throw new UsageException("missing environment variable " + name);
            }
            return value;
        }

        List<String> passwords() {
            List<String> values = new ArrayList<>(2);
            if (!sourcePassword.isEmpty()) {
                values.add(sourcePassword);
            }
            if (!targetPassword.isEmpty()) {
                values.add(targetPassword);
            }
            return values;
        }

        @Override
        public String toString() {
            return "Secrets[redacted]";
        }
    }

    private static Path validateSourceUrl(String url) throws IOException {
        if (url == null || !url.startsWith(SOURCE_FILE_PREFIX)) {
            throw new UsageException(ENV_SOURCE_URL + " must be an existing local "
                    + SOURCE_FILE_PREFIX + " URL");
        }
        String upper = url.toUpperCase(Locale.ROOT);
        for (String forbidden : List.of(";INIT=", ";RUNSCRIPT", ";SCRIPT=", ";AUTO_SERVER",
                ";AUTO_RECONNECT", ";USER=", ";PASSWORD=", ";CREATE=TRUE", "JDBC:H2:TCP",
                "JDBC:H2:SSL", "JDBC:H2:MEM", "JDBC:H2:ZIP")) {
            if (upper.contains(forbidden)) {
                throw new UsageException(ENV_SOURCE_URL + " contains a forbidden H2 setting ("
                        + forbidden.substring(1) + ")");
            }
        }
        Map<String, String> settings = h2Settings(url);
        requireH2Setting(settings, "IFEXISTS", "TRUE");
        requireH2Setting(settings, "ACCESS_MODE_DATA", "r");
        requireH2Setting(settings, "LAZY_QUERY_EXECUTION", "TRUE");

        String file = sourceLocator(url);
        if (file.isBlank()) {
            throw new UsageException(ENV_SOURCE_URL + " does not name an H2 database file");
        }
        if ("~".equals(file)) {
            file = System.getProperty("user.home");
        } else if (file.startsWith("~/")) {
            file = System.getProperty("user.home") + file.substring(1);
        }
        Path path = Paths.get(file);
        Path base;
        Path dataFile;
        if (file.endsWith(".mv.db")) {
            dataFile = path;
            base = Paths.get(file.substring(0, file.length() - ".mv.db".length()));
        } else {
            dataFile = Paths.get(file + ".mv.db");
            base = path;
        }
        if (!Files.isRegularFile(dataFile) || !Files.isReadable(dataFile)) {
            throw new UsageException("source H2 database file is missing or unreadable");
        }
        Path lock = Paths.get(base.toString() + ".lock.db");
        if (Files.exists(lock)) {
            throw new UsageException("source H2 database appears to be open (lock file exists); "
                    + "stop H2 cleanly and retry");
        }
        return dataFile.toAbsolutePath();
    }

    private static String sourceLocator(String url) {
        int separator = url.indexOf(';', SOURCE_FILE_PREFIX.length());
        return url.substring(SOURCE_FILE_PREFIX.length(), separator < 0 ? url.length() : separator);
    }

    private static void verifyManifestDestination(Path sourceFile, Path manifest) throws IOException {
        if (sourceFile.equals(manifest)
                || (!Files.notExists(manifest) && Files.isSameFile(sourceFile, manifest))) {
            throw new UsageException("manifest must not refer to the H2 source");
        }
    }

    private static Map<String, String> h2Settings(String url) {
        Map<String, String> settings = new LinkedHashMap<>();
        int separator = url.indexOf(';');
        if (separator < 0) {
            return settings;
        }
        String[] parts = url.substring(separator + 1).split(";");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            int equals = part.indexOf('=');
            if (equals < 0) {
                throw new UsageException("H2 URL setting has no value: " + part);
            }
            String key = part.substring(0, equals).trim().toUpperCase(Locale.ROOT);
            String value = part.substring(equals + 1);
            if (settings.putIfAbsent(key, value) != null) {
                throw new UsageException("duplicate H2 URL setting: " + key);
            }
        }
        return settings;
    }

    private static void requireH2Setting(Map<String, String> settings, String key, String value) {
        String actual = settings.get(key);
        if (actual == null) {
            throw new UsageException(ENV_SOURCE_URL + " must include " + key + "=" + value);
        }
        if (!actual.equalsIgnoreCase(value)) {
            throw new UsageException(ENV_SOURCE_URL + " must set " + key + "=" + value);
        }
    }

    private static void validateTargetUrl(String url) {
        if (url == null || !url.startsWith(TARGET_URL_PREFIX)) {
            throw new UsageException(ENV_TARGET_URL + " must start with " + TARGET_URL_PREFIX);
        }
        if (EMBEDDED_CREDENTIAL.matcher(url).find() || URL_USERINFO.matcher(url).find()) {
            throw new UsageException(ENV_TARGET_URL + " must not embed user/password credentials");
        }
        Matcher currentSchema = CURRENT_SCHEMA.matcher(url);
        if (currentSchema.find() && !"public".equalsIgnoreCase(currentSchema.group(1))) {
            throw new UsageException(ENV_TARGET_URL + " must not select a non-public current schema");
        }
        Matcher mode = QUERY_MODE.matcher(url);
        if (mode.find() && !"extended".equalsIgnoreCase(mode.group(1))) {
            throw new UsageException(ENV_TARGET_URL
                    + " must use preferQueryMode=extended so streaming uses server cursors");
        }
    }

    /* --------------------------------------------------------------------- */
    /* Logging, masking, and report                                          */
    /* --------------------------------------------------------------------- */

    private static void quietLogging() {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        try {
            Class<?> levelType = Class.forName("ch.qos.logback.classic.Level");
            Object warn = levelType.getField("WARN").get(null);
            Class<?> loggerType = Class.forName("ch.qos.logback.classic.Logger");
            Object context = org.slf4j.LoggerFactory.getILoggerFactory();
            Object root = context.getClass().getMethod("getLogger", String.class)
                    .invoke(context, org.slf4j.Logger.ROOT_LOGGER_NAME);
            if (loggerType.isInstance(root)) {
                root.getClass().getMethod("setLevel", levelType).invoke(root, warn);
            }
        } catch (Throwable ignored) {
            // Logging configuration is best-effort; no application context is involved.
        }
    }

    private static String mask(Throwable failure, Secrets secrets, Path sourceFile) {
        if (failure == null) {
            return "unknown failure";
        }
        String message;
        if (failure instanceof SQLException sql) {
            message = "SQLState=" + safe(sql.getSQLState()) + ", errorCode=" + sql.getErrorCode();
        } else {
            message = failure.getClass().getSimpleName();
            String detail = failure.getMessage();
            if (detail != null && !detail.isBlank()) {
                message += ": " + detail;
            }
        }
        if (secrets != null && secrets.sourceUrl().startsWith(SOURCE_FILE_PREFIX)) {
            String locator = sourceLocator(secrets.sourceUrl());
            if (!locator.isBlank()) {
                message = message.replace(secrets.sourceUrl(), "[H2 source]");
                if (sourceFile != null) {
                    String path = sourceFile.toString();
                    message = message.replace(path, "[H2 source]");
                    if (path.endsWith(".mv.db")) {
                        message = message.replace(path.substring(0, path.length() - ".mv.db".length()), "[H2 source]");
                    }
                }
                message = message.replace(locator, "[H2 source]");
            }
        }
        return scrub(message, secrets);
    }

    private static String scrub(String value, Secrets secrets) {
        String result = value == null ? "" : value;
        if (secrets != null) {
            for (String password : secrets.passwords()) {
                result = result.replace(password, "***");
            }
        }
        result = PASSWORD_SETTING.matcher(result).replaceAll("$1=***");
        if (result.length() > MAX_REPORTED_MESSAGE) {
            result = result.substring(0, MAX_REPORTED_MESSAGE) + "...";
        }
        return result;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private enum CommitOutcome { NOT_ATTEMPTED, UNKNOWN, COMMITTED }

    private static final class Report {
        private final ObjectNode root = MANIFEST_JSON.createObjectNode();
        private final ObjectNode source;
        private final ObjectNode target;
        private final ArrayNode tables;
        private final ObjectNode foreignKeys;
        private final ArrayNode foreignKeyDefinitions;
        private final ObjectNode flywayHistory;
        private final ObjectNode rollback;
        private CommitOutcome commitOutcome = CommitOutcome.NOT_ATTEMPTED;

        Report(Options options) {
            root.put("tool", "H2PostgresCutover");
            root.put("manifestVersion", 1);
            root.put("generatedAt", Instant.now().toString());
            root.put("mode", options.execute() ? "execute" : "dry-run");
            root.put("status", "FAILED");
            commitOutcome(CommitOutcome.NOT_ATTEMPTED);
            root.put("expectedVersion", options.expectedVersion());
            source = root.putObject("source");
            target = root.putObject("target");
            tables = root.putArray("tables");
            foreignKeys = root.putObject("foreignKeys");
            foreignKeyDefinitions = foreignKeys.putArray("definitions");
            flywayHistory = root.putObject("flywayHistory");
            rollback = root.putObject("rollback");
            rollback.put("attempted", false);
            rollback.put("succeeded", false);
        }

        void source(String h2Version, String schema, String currentVersion, int applied) {
            source.put("h2Version", h2Version);
            source.put("schema", schema);
            source.put("flywayCurrentVersion", currentVersion);
            source.put("flywayAppliedMigrations", applied);
            source.put("flywayChecksumsValidated", true);
        }

        void sourceRows(long rows) {
            source.put("rowCount", rows);
        }

        void sourceFingerprintBefore(byte[] digest) {
            source.put("sha256Before", hex(digest));
        }

        void sourceFingerprintAfter(byte[] digest, boolean matches) {
            source.put("sha256After", hex(digest));
            source.put("fingerprintMatches", matches);
        }

        void targetUrl(String url) {
            target.put("jdbcUrl", sanitizeUrl(url));
        }

        void target(String version, int versionNum, String encoding, boolean initiallyEmpty,
                    long relations, long routines, long customTypes) {
            target.put("serverVersion", version);
            target.put("serverVersionNum", versionNum);
            target.put("serverEncoding", encoding);
            target.put("initiallyEmptyPublicSchema", initiallyEmpty);
            target.put("publicSchemaRelationObjects", relations);
            target.put("publicSchemaRoutines", routines);
            target.put("publicSchemaCustomTypes", customTypes);
        }

        void targetMigrated(MigrateResult result) {
            target.put("flywayMigrated", true);
            target.put("flywayMigrationsExecuted", result.migrationsExecuted);
            target.put("flywayTargetSchemaVersion", result.targetSchemaVersion);
            target.put("flywaySchemaName", result.schemaName);
            target.put("flywayVersion", result.flywayVersion);
        }

        void addTable(String name, List<String> columns, List<String> primaryKey, long rows,
                      byte[] digest) {
            ObjectNode table = tables.addObject();
            table.put("name", name);
            ArrayNode columnArray = table.putArray("columns");
            columns.forEach(columnArray::add);
            ArrayNode keyArray = table.putArray("primaryKey");
            primaryKey.forEach(keyArray::add);
            table.put("rowCount", rows);
            if (digest != null) {
                table.put("sha256", hex(digest));
            }
        }

        void foreignKeyCaptured(int count) {
            foreignKeys.put("count", count);
            foreignKeys.put("dropped", false);
            foreignKeys.put("restored", false);
            foreignKeys.put("validated", false);
        }

        void foreignKeyDefinition(String table, String name, String definition, boolean validated) {
            ObjectNode node = foreignKeyDefinitions.addObject();
            node.put("table", table);
            node.put("name", name);
            node.put("definition", definition);
            node.put("validatedBeforeCopy", validated);
        }

        void foreignKeysDropped() {
            foreignKeys.put("dropped", true);
        }

        void foreignKeysRestored(boolean validated) {
            foreignKeys.put("restored", true);
            foreignKeys.put("validated", validated);
        }

        void seedsCleared(long rows) {
            target.put("targetSeedRowsClearedAfterVerifiedEmpty", rows);
        }

        void rowsCopied(long rows) {
            target.put("rowsCopied", rows);
        }

        void history(String sourceTable, String targetTable, long sourceRows, long targetRows,
                     byte[] digest) {
            flywayHistory.put("table", targetTable);
            flywayHistory.put("sourceTable", sourceTable);
            flywayHistory.put("sourceRows", sourceRows);
            flywayHistory.put("targetRows", targetRows);
            flywayHistory.put("copied", true);
            if (digest != null) {
                flywayHistory.put("sha256", hex(digest));
            }
        }

        void historyValidatedAfterCommit(boolean validated) {
            flywayHistory.put("validatedAfterCommit", validated);
        }

        void targetAppliedMigrationsAfterHistoryCopy(int applied) {
            target.put("flywayAppliedMigrationsAfterHistoryCopy", applied);
        }

        void rollbackAttempted(boolean succeeded) {
            rollback.put("attempted", true);
            rollback.put("succeeded", succeeded);
        }

        void commitOutcome(CommitOutcome outcome) {
            commitOutcome = outcome;
            root.put("commitOutcome", outcome.name());
            root.put("committed", outcome == CommitOutcome.COMMITTED);
        }

        void status(String status) {
            root.put("status", status);
        }

        void fail(Throwable failure, String masked) {
            root.put("status", "FAILED");
            ObjectNode error = root.putObject("error");
            error.put("type", failure.getClass().getName());
            error.put("message", masked);
            if (failure instanceof SQLException sql) {
                error.put("sqlState", sql.getSQLState());
                error.put("sqlErrorCode", sql.getErrorCode());
            }
        }

        void write(Path path) throws IOException {
            Path absolute = path.toAbsolutePath().normalize();
            Path directory = absolute.getParent();
            if (directory == null) {
                directory = Paths.get(".").toAbsolutePath().normalize();
            }
            if (!Files.isDirectory(directory)) {
                throw new IOException("manifest directory does not exist: " + directory);
            }
            Path temporary = Files.createTempFile(directory, ".h2pg-cutover-", ".tmp");
            try {
                try {
                    Files.setPosixFilePermissions(temporary,
                            PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // Non-POSIX filesystem; the temp file is still created private by Java.
                }
                byte[] bytes = MANIFEST_JSON.writeValueAsBytes(root);
                Files.write(temporary, bytes, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String sanitizeUrl(String url) {
        if (url == null) {
            return null;
        }
        String sanitized = PASSWORD_SETTING.matcher(url).replaceAll("$1=***");
        return sanitized.replaceAll("//[^/@\\s]+@", "//***@");
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >>> 4) & 0xF, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private static final class CutoverException extends RuntimeException {
        CutoverException(String message) {
            super(message);
        }
    }

    private static Flyway flyway(Secrets secrets, boolean target, String schema) {
        String url = target ? secrets.targetUrl() : secrets.sourceUrl();
        String user = target ? secrets.targetUser() : secrets.sourceUser();
        String password = target ? secrets.targetPassword() : secrets.sourcePassword();
        var configuration = Flyway.configure(H2PostgresCutover.class.getClassLoader())
                .dataSource(url, user, password)
                .locations(MIGRATION_LOCATIONS)
                .defaultSchema(schema)
                .createSchemas(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .baselineOnMigrate(false)
                .failOnMissingLocations(true);
        if (target) {
            // Keep unqualified migration DDL in the one schema this tool verified as public.
            configuration = configuration.initSql("SET search_path TO public");
        }
        return configuration.load();
    }

    /* --------------------------------------------------------------------- */
    /* JDBC helpers                                                          */
    /* --------------------------------------------------------------------- */

    private static Connection openSource(Secrets secrets) {
        loadDrivers();
        try {
            Connection connection = DriverManager.getConnection(secrets.sourceUrl(),
                    secrets.sourceUser(), secrets.sourcePassword());
            try {
                connection.setReadOnly(true);
            } catch (SQLException ignored) {
                // The H2 URL ACCESS_MODE_DATA=r already enforces read-only access.
            }
            return connection;
        } catch (SQLException e) {
            throw sqlFailure("open source H2 connection", e);
        }
    }

    private static Connection openTarget(Secrets secrets) {
        loadDrivers();
        try {
            Connection connection = DriverManager.getConnection(secrets.targetUrl(),
                    secrets.targetUser(), secrets.targetPassword());
            connection.setAutoCommit(true);
            return connection;
        } catch (SQLException e) {
            throw sqlFailure("open target PostgreSQL connection", e);
        }
    }

    private static void loadDrivers() {
        try {
            Class.forName("org.h2.Driver");
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new UsageException("JDBC driver is not present on the application classpath: "
                    + e.getMessage());
        }
    }

    private static String singleString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("query returned no row");
            }
            return result.getString(1);
        }
    }

    private static long singleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("query returned no row");
            }
            return result.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /* --------------------------------------------------------------------- */
    /* Orchestration                                                         */
    /* --------------------------------------------------------------------- */

    private static final class CutoverRun {
        private final Options options;
        private final Secrets secrets;
        private final Report report;
        private final Path sourceFile;
        private byte[] sourceDigest;
        private boolean fingerprintRecorded;

        CutoverRun(Options options, Secrets secrets, Report report, Path sourceFile) {
            this.options = options;
            this.secrets = secrets;
            this.report = report;
            this.sourceFile = sourceFile;
        }

        void run() throws Exception {
            report.targetUrl(secrets.targetUrl());

            try (Connection target = openTarget(secrets)) {
                target.setReadOnly(true);
                TargetInfo initialTarget = verifyTargetEmpty(target);
                report.target(initialTarget.version(), initialTarget.versionNum(),
                        initialTarget.encoding(), initialTarget.initiallyEmpty(),
                        initialTarget.relations(), initialTarget.routines(),
                        initialTarget.customTypes());
                sourceDigest = fileSha256(sourceFile);
                report.sourceFingerprintBefore(sourceDigest);

                try (Connection source = openSource(secrets)) {
                    SourceInfo sourceInfo = verifySource(source, secrets,
                            options.expectedVersion());
                    report.source(sourceInfo.h2Version(), sourceInfo.schema(),
                            sourceInfo.currentVersion(), sourceInfo.applied());
                    Map<String, TableMeta> sourceTables =
                            readBusinessTables(source, sourceInfo.schema());

                    if (!options.execute()) {
                        dryRun(source, sourceTables);
                    } else {
                        execute(target, source, sourceInfo, sourceTables, initialTarget);
                    }
                }
                if (!options.execute()) {
                    verifySourceFingerprint();
                    report.status("DRY_RUN");
                }
            } catch (Exception failure) {
                if (sourceDigest != null && !fingerprintRecorded) {
                    try {
                        verifySourceFingerprint();
                    } catch (Exception fingerprintFailure) {
                        failure.addSuppressed(fingerprintFailure);
                    }
                }
                throw failure;
            }
        }

        private void verifySourceFingerprint() throws IOException {
            byte[] after = fileSha256(sourceFile);
            boolean matches = MessageDigest.isEqual(sourceDigest, after);
            report.sourceFingerprintAfter(after, matches);
            fingerprintRecorded = true;
            if (!matches) {
                throw new CutoverException("the H2 source SHA-256 changed during cutover");
            }
        }

        private void dryRun(Connection source, Map<String, TableMeta> sourceTables)
                throws Exception {
            long totalRows = 0;
            for (TableMeta table : sourceTables.values()) {
                long rows = countRows(source, table);
                totalRows += rows;
                report.addTable(table.name(), columnNames(table), table.primaryKey(), rows, null);
            }
            report.sourceRows(totalRows);
        }

        private void execute(Connection target, Connection source, SourceInfo sourceInfo,
                             Map<String, TableMeta> sourceTables, TargetInfo initialTarget)
                throws Exception {
            // Re-check immediately before Flyway creates objects.  The first check made this the
            // only database the tool is allowed to populate; the second narrows the race window.
            TargetInfo beforeMigration = verifyTargetEmpty(target);
            if (!initialTarget.initiallyEmpty() || !beforeMigration.initiallyEmpty()) {
                throw new CutoverException("target public schema was not verified empty");
            }

            target.setReadOnly(false);
            Flyway targetFlyway = flyway(secrets, true, "public");
            MigrateResult migrated;
            try {
                migrated = targetFlyway.migrate();
            } catch (RuntimeException e) {
                throw new CutoverException("target Flyway migrate failed: "
                        + flywayMessage(e));
            }
            if (!migrated.success) {
                throw new CutoverException("target Flyway migrate did not report success");
            }
            report.targetMigrated(migrated);

            Map<String, TableMeta> targetTables = readBusinessTables(target, "public");
            compareSchemas(sourceTables, targetTables);

            String targetHistoryTable = findHistoryTable(target, "public");
            TableMeta sourceHistory = readTableMeta(source, sourceInfo.schema(),
                    sourceInfo.historyTable());
            TableMeta targetHistory = readTableMeta(target, "public", targetHistoryTable);
            compareTable(sourceHistory, targetHistory, "flyway history");

            Map<String, TablePlan> plans = buildPlans(sourceTables, targetTables);
            TablePlan historyPlan = new TablePlan(sourceHistory, targetHistory);

            DataResult data = runDataTransaction(source, target, targetTables, plans, historyPlan,
                    initialTarget);
            report.rowsCopied(data.rowsCopied());

            postCommit(target, targetFlyway, data.foreignKeys(), options.expectedVersion());

            report.status("COMPLETED");
        }

        private DataResult runDataTransaction(Connection source, Connection target,
                                              Map<String, TableMeta> targetTables,
                                              Map<String, TablePlan> plans,
                                              TablePlan historyPlan,
                                              TargetInfo initialTarget) throws Exception {
            if (!initialTarget.initiallyEmpty()) {
                throw new CutoverException("refusing to clear target seeds: initial target was "
                        + "not verified empty");
            }
            long rowsCopied = 0;
            boolean committed = false;
            List<ForeignKey> foreignKeys = List.of();
            target.setAutoCommit(false);
            try {
                H2PostgresCutover.execute(target, "SET LOCAL search_path TO public");
                foreignKeys = readForeignKeys(target);
                report.foreignKeyCaptured(foreignKeys.size());
                for (ForeignKey foreignKey : foreignKeys) {
                    report.foreignKeyDefinition(foreignKey.table(), foreignKey.name(),
                            foreignKey.definition(), foreignKey.validated());
                }
                dropForeignKeys(target, foreignKeys);
                report.foreignKeysDropped();

                long seeds = deleteAll(target, targetTables);
                report.seedsCleared(seeds);

                for (TablePlan plan : plans.values()) {
                    TableResult result = copyTable(plan, source, target);
                    rowsCopied += result.sourceRows();
                    report.addTable(plan.name, columnNames(plan.source), plan.source.primaryKey(),
                            result.sourceRows(), result.sourceDigest());
                }

                // H2's Flyway history table carries an installed_rank=-1, type=TABLE creation
                // marker.  PostgreSQL Flyway starts its history cache at -1 and selects rows
                // with installed_rank > -1, so preserving the complete source history is
                // faithful without turning that marker into an applied migration.
                try {
                    executeUpdate(target, "DELETE FROM " + historyPlan.target.qualified());
                } catch (SQLException e) {
                    throw sqlFailure("delete target Flyway history rows", e);
                }
                TableResult history = copyTable(historyPlan, source, target);
                report.history(historyPlan.source.qualified(), historyPlan.target.qualified(),
                        history.sourceRows(), history.targetRows(), history.sourceDigest());

                restoreForeignKeys(target, foreignKeys);
                report.foreignKeysRestored(true);

                // The final source digest includes any effects of closing the H2 connection.
                source.close();
                verifySourceFingerprint();
                report.commitOutcome(CommitOutcome.UNKNOWN);
                target.commit();
                committed = true;
                report.commitOutcome(CommitOutcome.COMMITTED);
            } catch (Exception failure) {
                boolean rolledBack = false;
                try {
                    target.rollback();
                    rolledBack = true;
                } catch (Exception ignored) {
                    // The report records that rollback was attempted and did not succeed.
                }
                report.rollbackAttempted(rolledBack);
                throw failure;
            } finally {
                if (committed) {
                    try {
                        target.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // The commit already happened; closing the connection releases it.
                    }
                }
            }
            return new DataResult(rowsCopied, foreignKeys);
        }

        private void postCommit(Connection target, Flyway targetFlyway,
                                List<ForeignKey> foreignKeys, String expectedVersion)
                throws Exception {
            int applied = -1;
            try {
                targetFlyway.validate();
                MigrationInfoService info = targetFlyway.info();
                MigrationInfo current = info.current();
                MigrationVersion expected = MigrationVersion.fromVersion(expectedVersion);
                if (current == null || current.getVersion() == null
                        || !expected.equals(current.getVersion())) {
                    throw new CutoverException("target Flyway current version does not match "
                            + expectedVersion + " after history copy");
                }
                if (info.pending().length != 0) {
                    throw new CutoverException("target Flyway reports pending migrations after "
                            + "history copy");
                }
                applied = info.applied().length;
            } catch (CutoverException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new CutoverException("target Flyway validation after commit failed: "
                        + flywayMessage(e));
            }
            verifyForeignKeys(target, foreignKeys);
            report.targetAppliedMigrationsAfterHistoryCopy(applied);
            report.historyValidatedAfterCommit(true);
            report.foreignKeysRestored(true);
        }
    }

    private record SourceInfo(String schema, String historyTable, String h2Version,
                              String currentVersion, int applied) {
    }

    private record TargetInfo(String version, int versionNum, String encoding,
                              boolean initiallyEmpty, long relations, long routines,
                              long customTypes) {
    }

    private record DataResult(long rowsCopied, List<ForeignKey> foreignKeys) {
    }

    private record TableResult(String name, long sourceRows, long targetRows,
                               byte[] sourceDigest, byte[] targetDigest) {
    }

    private record ForeignKey(String table, String name, String definition, boolean validated) {
    }

    private record TableName(String schema, String name, String type) {
    }

    private record ColumnMeta(String name, LogicalType type, int jdbcType, String typeName,
                              int precision, int scale, boolean autoIncrement) {
    }

    private record TableMeta(String schema, String name, List<ColumnMeta> columns,
                             List<String> primaryKey, String qualified) {
        ColumnMeta column(String columnName) {
            for (ColumnMeta column : columns) {
                if (column.name().equals(columnName)) {
                    return column;
                }
            }
            throw new CutoverException("primary key column is missing from table " + name + ": "
                    + columnName);
        }
    }

    private enum LogicalType {
        TEXT, DECIMAL, INTEGER, BIGINT, BOOLEAN, BINARY, DATE, TIME, TIMESTAMP, TIMESTAMPTZ,
        UUID, REAL, DOUBLE, JSON
    }

    /* --------------------------------------------------------------------- */
    /* Metadata                                                              */
    /* --------------------------------------------------------------------- */

    private static SourceInfo verifySource(Connection source, Secrets secrets,
                                           String expectedVersion) throws SQLException {
        String h2Version;
        try {
            h2Version = singleString(source, "SELECT H2VERSION()");
        } catch (SQLException e) {
            throw sqlFailure("read source H2 version", e);
        }
        String schema = source.getSchema();
        if (schema == null || schema.isBlank()) {
            try {
                schema = singleString(source, "SELECT SCHEMA()");
            } catch (SQLException first) {
                try {
                    schema = singleString(source, "SELECT CURRENT_SCHEMA()");
                } catch (SQLException second) {
                    throw sqlFailure("determine source schema", second);
                }
            }
        }
        if (schema == null || schema.isBlank()) {
            throw new CutoverException("could not determine the source H2 schema");
        }
        List<TableName> historyCandidates = new ArrayList<>();
        for (TableName table : listTables(source)) {
            if (isOrdinaryTable(table)
                    && table.name().equalsIgnoreCase(FLYWAY_HISTORY_TABLE)) {
                historyCandidates.add(table);
            }
        }
        if (historyCandidates.size() == 1) {
            schema = historyCandidates.get(0).schema();
        } else if (historyCandidates.size() > 1) {
            throw new CutoverException("multiple Flyway history tables exist in the source "
                    + "database; expected exactly one application schema");
        }
        String historyTable = findHistoryTable(source, schema);

        MigrationVersion expected = MigrationVersion.fromVersion(expectedVersion);
        Flyway sourceFlyway = flyway(secrets, false, schema);
        MigrationInfoService info;
        MigrationInfo current;
        try {
            info = sourceFlyway.info();
            current = info.current();
        } catch (RuntimeException e) {
            throw new CutoverException("source Flyway info failed: " + flywayMessage(e));
        }
        if (current == null || current.getVersion() == null) {
            throw new CutoverException("source Flyway history has no current version");
        }
        if (!expected.equals(current.getVersion())) {
            throw new CutoverException("source Flyway version is " + current.getVersion()
                    + " but --expected-version is " + expectedVersion);
        }
        try {
            sourceFlyway.validate();
        } catch (RuntimeException e) {
            throw new CutoverException("source Flyway checksum/set validation failed: "
                    + flywayMessage(e));
        }
        MigrationInfoService validated;
        try {
            validated = sourceFlyway.info();
        } catch (RuntimeException e) {
            throw new CutoverException("source Flyway info after validation failed: "
                    + flywayMessage(e));
        }
        if (validated.pending().length != 0) {
            throw new CutoverException("source Flyway has pending migrations; expected an exact "
                    + "schema at version " + expectedVersion);
        }
        return new SourceInfo(schema, historyTable, h2Version.trim(),
                current.getVersion().getVersion(), validated.applied().length);
    }

    private static TargetInfo verifyTargetEmpty(Connection target) throws SQLException {
        String version;
        String versionNumText;
        String encoding;
        try {
            version = singleString(target, "SHOW server_version");
            versionNumText = singleString(target,
                    "SELECT current_setting('server_version_num')");
            encoding = singleString(target, "SHOW server_encoding");
        } catch (SQLException e) {
            throw sqlFailure("read target PostgreSQL version/encoding", e);
        }
        int versionNum;
        try {
            versionNum = Integer.parseInt(versionNumText.trim());
        } catch (NumberFormatException e) {
            throw new CutoverException("target server_version_num is not an integer");
        }
        if (versionNum / 10000 != 17) {
            throw new CutoverException("target PostgreSQL major version is "
                    + (versionNum / 10000) + "; PostgreSQL 17 is required");
        }
        if (!"UTF8".equalsIgnoreCase(encoding.trim())) {
            throw new CutoverException("target PostgreSQL database encoding is " + encoding.trim()
                    + "; UTF8 is required");
        }

        long publicSchema;
        long relations;
        long routines;
        long customTypes;
        try {
            publicSchema = singleLong(target,
                    "SELECT count(*) FROM pg_namespace WHERE nspname='public'");
            relations = singleLong(target,
                    "SELECT count(*) FROM pg_class c JOIN pg_namespace n "
                            + "ON n.oid=c.relnamespace WHERE n.nspname='public'");
            routines = singleLong(target,
                    "SELECT count(*) FROM pg_proc p JOIN pg_namespace n "
                            + "ON n.oid=p.pronamespace WHERE n.nspname='public'");
            customTypes = singleLong(target,
                    "SELECT count(*) FROM pg_type t JOIN pg_namespace n "
                            + "ON n.oid=t.typnamespace WHERE n.nspname='public' "
                            + "AND t.typtype IN ('e','d','c')");
        } catch (SQLException e) {
            throw sqlFailure("inspect target public schema", e);
        }
        if (publicSchema != 1) {
            throw new CutoverException("target public schema does not exist");
        }
        if (relations != 0 || routines != 0 || customTypes != 0) {
            throw new CutoverException("target public schema is not empty: relations=" + relations
                    + ", routines=" + routines + ", customTypes=" + customTypes
                    + "; refusing to populate or delete anything");
        }
        return new TargetInfo(version.trim(), versionNum, encoding.trim(), true, relations,
                routines, customTypes);
    }

    private static List<TableName> listTables(Connection connection) throws SQLException {
        Map<String, TableName> tables = new LinkedHashMap<>();
        collectTables(connection, null, tables);
        return new ArrayList<>(tables.values());
    }

    private static void collectTables(Connection connection, String[] types,
                                      Map<String, TableName> tables) throws SQLException {
        try (ResultSet result = connection.getMetaData().getTables(null, null, "%", types)) {
            while (result.next()) {
                String type = result.getString("TABLE_TYPE");
                String schema = result.getString("TABLE_SCHEM");
                String name = result.getString("TABLE_NAME");
                if (type == null || schema == null || name == null) {
                    continue;
                }
                tables.putIfAbsent(schema + "\u0000" + name,
                        new TableName(schema, name, type));
            }
        }
    }

    private static boolean isOrdinaryTable(TableName table) {
        return "TABLE".equalsIgnoreCase(table.type())
                || "BASE TABLE".equalsIgnoreCase(table.type());
    }

    private static String findHistoryTable(Connection connection, String schema)
            throws SQLException {
        String found = null;
        for (TableName table : listTables(connection)) {
            if (!schema.equals(table.schema()) || !isOrdinaryTable(table)
                    || !table.name().equalsIgnoreCase(FLYWAY_HISTORY_TABLE)) {
                continue;
            }
            if (found != null && !found.equals(table.name())) {
                throw new CutoverException("multiple Flyway history tables exist in schema "
                        + schema);
            }
            found = table.name();
        }
        if (found == null) {
            throw new CutoverException("Flyway history table " + FLYWAY_HISTORY_TABLE
                    + " was not found in schema " + schema
                    + "; preserve the source database's identifier-case settings in the URL");
        }
        return found;
    }

    private static Map<String, TableMeta> readBusinessTables(Connection connection, String schema)
            throws SQLException {
        Map<String, TableMeta> tables = new TreeMap<>();
        for (TableName table : listTables(connection)) {
            if (!schema.equals(table.schema())
                    || table.name().equalsIgnoreCase(FLYWAY_HISTORY_TABLE)) {
                continue;
            }
            if (table.type().equalsIgnoreCase("PARTITIONED TABLE")
                    || table.type().equalsIgnoreCase("FOREIGN TABLE")) {
                throw new CutoverException("table " + table.name() + " is a " + table.type()
                        + "; partitioned/foreign tables are not supported");
            }
            if (!isOrdinaryTable(table)) {
                continue;
            }
            tables.put(table.name(), readTableMeta(connection, schema, table.name()));
        }
        if (tables.isEmpty()) {
            throw new CutoverException("no business tables found in schema " + schema);
        }
        return tables;
    }

    private static TableMeta readTableMeta(Connection connection, String schema, String table)
            throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>();
        String qualified = quote(schema) + "." + quote(table);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM " + qualified + " WHERE 1=0")) {
            ResultSetMetaData metadata = result.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String name = metadata.getColumnName(i);
                int jdbcType = metadata.getColumnType(i);
                String typeName = metadata.getColumnTypeName(i);
                LogicalType type;
                try {
                    type = logicalType(jdbcType, typeName);
                } catch (CutoverException e) {
                    throw new CutoverException("table " + table + " column " + name + ": "
                            + e.getMessage());
                }
                boolean autoIncrement = metadata.isAutoIncrement(i);
                if (autoIncrement) {
                    throw new CutoverException("table " + table + " column " + name
                            + " is auto-increment/identity; sequence counters are not supported "
                            + "by this cutover tool");
                }
                columns.add(new ColumnMeta(name, type, jdbcType, typeName,
                        metadata.getPrecision(i), metadata.getScale(i), false));
            }
        } catch (SQLException e) {
            throw sqlFailure("read metadata for table " + table, e);
        }
        if (columns.isEmpty()) {
            throw new CutoverException("table " + table + " has no columns");
        }

        TreeMap<Integer, String> primaryKey = new TreeMap<>();
        try (ResultSet keys = connection.getMetaData().getPrimaryKeys(null, schema, table)) {
            while (keys.next()) {
                int sequence = keys.getInt("KEY_SEQ");
                String column = keys.getString("COLUMN_NAME");
                if (primaryKey.putIfAbsent(sequence, column) != null) {
                    throw new CutoverException("table " + table
                            + " has an ambiguous primary key");
                }
            }
        } catch (SQLException e) {
            throw sqlFailure("read primary key for table " + table, e);
        }
        if (primaryKey.isEmpty()) {
            throw new CutoverException("table " + table + " has no primary key; streaming a "
                    + "deterministic digest requires one");
        }
        TableMeta meta = new TableMeta(schema, table, List.copyOf(columns),
                List.copyOf(primaryKey.values()), qualified);
        for (String key : meta.primaryKey()) {
            meta.column(key);
        }
        return meta;
    }

    private static LogicalType logicalType(int jdbcType, String typeName) {
        String name = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
        // H2 reports its JSON-like and UUID types with driver-dependent JDBC codes; the declared
        // type name is authoritative for those two families.  Raw TEXT/JSON payloads stored in
        // ordinary character columns still take the exact-text path below.
        if ("json".equals(name) || "jsonb".equals(name)) {
            return LogicalType.JSON;
        }
        if ("uuid".equals(name)) {
            return LogicalType.UUID;
        }
        // PostgreSQL JDBC reports timestamptz through Types.TIMESTAMP; retain the declared
        // timezone-bearing family rather than treating its values as local timestamps.
        if ("timestamptz".equals(name) || "timestamp with time zone".equals(name)) {
            return LogicalType.TIMESTAMPTZ;
        }
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> LogicalType.TEXT;
            case Types.NUMERIC, Types.DECIMAL -> LogicalType.DECIMAL;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> LogicalType.INTEGER;
            case Types.BIGINT -> LogicalType.BIGINT;
            case Types.BOOLEAN -> LogicalType.BOOLEAN;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    LogicalType.BINARY;
            case Types.DATE -> LogicalType.DATE;
            case Types.TIME -> LogicalType.TIME;
            case Types.TIMESTAMP -> LogicalType.TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE -> LogicalType.TIMESTAMPTZ;
            case Types.REAL -> LogicalType.REAL;
            case Types.DOUBLE, Types.FLOAT -> LogicalType.DOUBLE;
            case Types.OTHER -> {
                if ("uuid".equals(name)) {
                    yield LogicalType.UUID;
                }
                if ("json".equals(name) || "jsonb".equals(name)) {
                    yield LogicalType.JSON;
                }
                throw new CutoverException("unsupported SQL type " + name);
            }
            case Types.BIT -> {
                if ("boolean".equals(name) || "bool".equals(name)) {
                    yield LogicalType.BOOLEAN;
                }
                throw new CutoverException("unsupported SQL type " + name);
            }
            default -> throw new CutoverException("unsupported SQL type " + name
                    + " (jdbc " + jdbcType + ")");
        };
    }

    private static void compareSchemas(Map<String, TableMeta> source,
                                       Map<String, TableMeta> target) {
        Set<String> missing = new TreeSet<>(source.keySet());
        missing.removeAll(target.keySet());
        Set<String> extra = new TreeSet<>(target.keySet());
        extra.removeAll(source.keySet());
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new CutoverException("source/target table mismatch; missingInTarget="
                    + preview(missing) + " extraInTarget=" + preview(extra)
                    + " (if every name differs only by case, the source URL must preserve the "
                    + "database's lower-case identifier mode)");
        }
        for (String name : source.keySet()) {
            compareTable(source.get(name), target.get(name), "table " + name);
        }
    }

    private static void compareTable(TableMeta source, TableMeta target, String context) {
        if (source.columns().size() != target.columns().size()) {
            throw new CutoverException(context + " column count mismatch: source="
                    + source.columns().size() + " target=" + target.columns().size());
        }
        for (int i = 0; i < source.columns().size(); i++) {
            ColumnMeta a = source.columns().get(i);
            ColumnMeta b = target.columns().get(i);
            if (!a.name().equals(b.name())) {
                throw new CutoverException(context + " column " + (i + 1) + " name mismatch: "
                        + a.name() + " vs " + b.name());
            }
            if (a.type() != b.type()) {
                throw new CutoverException(context + " column " + a.name() + " type mismatch: "
                        + a.typeName() + " vs " + b.typeName());
            }
            if (a.type() == LogicalType.DECIMAL && a.precision() > 0 && b.precision() > 0
                    && (a.precision() != b.precision() || a.scale() != b.scale())) {
                throw new CutoverException(context + " column " + a.name()
                        + " numeric precision/scale mismatch");
            }
            if ((a.type() == LogicalType.TEXT || a.type() == LogicalType.BINARY)
                    && boundedLength(a.precision()) >= 0 && boundedLength(b.precision()) >= 0
                    && a.precision() != b.precision()) {
                throw new CutoverException(context + " column " + a.name()
                        + " declared length mismatch: " + a.precision() + " vs "
                        + b.precision());
            }
        }
        if (!source.primaryKey().equals(target.primaryKey())) {
            throw new CutoverException(context + " primary key mismatch: "
                    + source.primaryKey() + " vs " + target.primaryKey());
        }
    }

    private static int boundedLength(int precision) {
        return precision > 0 && precision < 1_000_000_000 ? precision : -1;
    }

    private static String preview(Set<String> names) {
        if (names.isEmpty()) {
            return "[]";
        }
        List<String> values = new ArrayList<>();
        int count = 0;
        for (String name : names) {
            values.add(name);
            if (++count == 8) {
                break;
            }
        }
        return values + (names.size() > values.size() ? " (+" + (names.size() - values.size())
                + " more)" : "");
    }

    private static List<String> columnNames(TableMeta table) {
        List<String> names = new ArrayList<>(table.columns().size());
        for (ColumnMeta column : table.columns()) {
            names.add(column.name());
        }
        return names;
    }

    /* --------------------------------------------------------------------- */
    /* Table plans and deterministic ordering                                */
    /* --------------------------------------------------------------------- */

    private static Map<String, TablePlan> buildPlans(Map<String, TableMeta> source,
                                                     Map<String, TableMeta> target) {
        Map<String, TablePlan> plans = new LinkedHashMap<>();
        for (Map.Entry<String, TableMeta> entry : source.entrySet()) {
            TableMeta targetTable = target.get(entry.getKey());
            if (targetTable == null) {
                throw new CutoverException("target table missing: " + entry.getKey());
            }
            plans.put(entry.getKey(), new TablePlan(entry.getValue(), targetTable));
        }
        return plans;
    }

    private static final class TablePlan {
        private final String name;
        private final TableMeta source;
        private final TableMeta target;
        private final String sourceSelect;
        private final String targetSelect;
        private final String insert;

        TablePlan(TableMeta source, TableMeta target) {
            this.name = source.name();
            this.source = source;
            this.target = target;
            String columns = quotedColumns(source.columns());
            this.sourceSelect = "SELECT " + columns + " FROM " + source.qualified()
                    + " ORDER BY " + orderBy(source, true);
            this.targetSelect = "SELECT " + columns + " FROM " + target.qualified()
                    + " ORDER BY " + orderBy(target, false);
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(target.qualified())
                    .append(" (").append(columns).append(") VALUES (");
            for (int i = 0; i < target.columns().size(); i++) {
                if (i != 0) {
                    sql.append(',');
                }
                sql.append(placeholder(target.columns().get(i)));
            }
            this.insert = sql.append(')').toString();
        }
    }

    private static String quotedColumns(List<ColumnMeta> columns) {
        List<String> names = new ArrayList<>(columns.size());
        for (ColumnMeta column : columns) {
            names.add(quote(column.name()));
        }
        return String.join(",", names);
    }

    private static String placeholder(ColumnMeta column) {
        if (column.type() != LogicalType.JSON) {
            return "?";
        }
        String type = column.typeName().toLowerCase(Locale.ROOT);
        if (!type.equals("json") && !type.equals("jsonb")) {
            throw new CutoverException("unsupported JSON target type " + column.typeName());
        }
        return "CAST(? AS " + type + ")";
    }

    private static String orderBy(TableMeta table, boolean h2) {
        List<String> expressions = new ArrayList<>(table.primaryKey().size());
        for (String key : table.primaryKey()) {
            expressions.add(orderExpression(table.column(key), h2));
        }
        return String.join(",", expressions);
    }

    private static String orderExpression(ColumnMeta column, boolean h2) {
        String quoted = quote(column.name());
        if (column.type() == LogicalType.TEXT) {
            boolean fixed = column.jdbcType() == Types.CHAR || column.jdbcType() == Types.NCHAR;
            if (h2) {
                String value = fixed ? "RTRIM(CAST(" + quoted + " AS VARCHAR))"
                        : "CAST(" + quoted + " AS VARCHAR)";
                return "STRINGTOUTF8(" + value + ")";
            }
            return "convert_to(CAST(" + quoted + " AS text),'UTF8')";
        }
        if (column.type() == LogicalType.UUID) {
            if (h2) {
                return "STRINGTOUTF8(CAST(" + quoted + " AS VARCHAR))";
            }
            return "convert_to(CAST(" + quoted + " AS text),'UTF8')";
        }
        if (column.type() == LogicalType.JSON || column.type() == LogicalType.REAL
                || column.type() == LogicalType.DOUBLE) {
            throw new CutoverException("unsupported primary key type " + column.type()
                    + " on column " + column.name());
        }
        return quoted;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /* --------------------------------------------------------------------- */
    /* Digest and value conversion                                           */
    /* --------------------------------------------------------------------- */

    private static final class TableDigest {
        private final MessageDigest digest = sha256();

        TableDigest(TableMeta table) {
            frame('H', table.name().getBytes(StandardCharsets.UTF_8));
            for (ColumnMeta column : table.columns()) {
                frame('C', column.name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) column.type().ordinal());
            }
        }

        void row() {
            digest.update((byte) 'R');
        }

        void value(ColumnMeta column, Object value) {
            if (value == null) {
                digest.update((byte) 0);
                return;
            }
            switch (column.type()) {
                case TEXT -> frame('S', ((String) value).getBytes(StandardCharsets.UTF_8));
                case DECIMAL -> frame('D', decimalText((BigDecimal) value)
                        .getBytes(StandardCharsets.US_ASCII));
                case INTEGER, BIGINT -> frame('I', Long.toString((Long) value)
                        .getBytes(StandardCharsets.US_ASCII));
                case BOOLEAN -> frame('B', new byte[]{((Boolean) value) ? (byte) 1 : (byte) 0});
                case BINARY -> frame('Y', (byte[]) value);
                case DATE -> frame('A', ((LocalDate) value).toString()
                        .getBytes(StandardCharsets.US_ASCII));
                case TIME -> frame('t', ((LocalTime) value).toString()
                        .getBytes(StandardCharsets.US_ASCII));
                case TIMESTAMP -> frame('P', ((LocalDateTime) value).toString()
                        .getBytes(StandardCharsets.US_ASCII));
                case TIMESTAMPTZ -> frame('Z', ((Instant) value).toString()
                        .getBytes(StandardCharsets.US_ASCII));
                case UUID -> frame('U', ((UUID) value).toString()
                        .getBytes(StandardCharsets.US_ASCII));
                case REAL -> frame('R', intBytes(Float.floatToIntBits((Float) value)));
                case DOUBLE -> frame('F', longBytes(Double.doubleToLongBits((Double) value)));
                case JSON -> frame('J', ((String) value).getBytes(StandardCharsets.UTF_8));
                default -> throw new CutoverException("unsupported digest type "
                        + column.type());
            }
        }

        byte[] finish(long rows) {
            frame('N', longBytes(rows));
            return digest.digest();
        }

        private void frame(int tag, byte[] payload) {
            digest.update((byte) tag);
            digest.update(intBytes(payload.length));
            digest.update(payload);
        }
    }

    private static String decimalText(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0);
        }
        return normalized.toPlainString();
    }

    private static byte[] intBytes(int value) {
        byte[] bytes = new byte[4];
        for (int i = 3; i >= 0; i--) {
            bytes[i] = (byte) value;
            value >>>= 8;
        }
        return bytes;
    }

    private static byte[] longBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) value;
            value >>>= 8;
        }
        return bytes;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] fileSha256(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    private static Object readCanonical(ResultSet result, int index, ColumnMeta column)
            throws SQLException {
        Object raw = result.getObject(index);
        if (raw == null) {
            return null;
        }
        return switch (column.type()) {
            case TEXT -> {
                if (raw instanceof Clob clob) {
                    long length = clob.length();
                    if (length > Integer.MAX_VALUE) {
                        throw unsupportedValue(column, raw);
                    }
                    yield length == 0 ? "" : clob.getSubString(1, (int) length);
                }
                if (raw instanceof byte[] bytes) {
                    yield new String(bytes, StandardCharsets.UTF_8);
                }
                yield raw.toString();
            }
            case DECIMAL -> {
                if (raw instanceof BigDecimal decimal) {
                    yield decimal;
                }
                if (raw instanceof Number number) {
                    yield new BigDecimal(number.toString());
                }
                try {
                    yield new BigDecimal(raw.toString());
                } catch (NumberFormatException e) {
                    throw unsupportedValue(column, raw);
                }
            }
            case INTEGER, BIGINT -> {
                if (raw instanceof Number number) {
                    yield number.longValue();
                }
                try {
                    yield Long.parseLong(raw.toString());
                } catch (NumberFormatException e) {
                    throw unsupportedValue(column, raw);
                }
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean bool) {
                    yield bool;
                }
                if (raw instanceof Number number) {
                    yield number.intValue() != 0;
                }
                throw unsupportedValue(column, raw);
            }
            case BINARY -> {
                if (raw instanceof byte[] bytes) {
                    yield bytes;
                }
                if (raw instanceof Blob blob) {
                    long length = blob.length();
                    if (length > Integer.MAX_VALUE) {
                        throw unsupportedValue(column, raw);
                    }
                    yield length == 0 ? new byte[0] : blob.getBytes(1, (int) length);
                }
                throw unsupportedValue(column, raw);
            }
            case DATE -> {
                if (raw instanceof LocalDate date) {
                    yield date;
                }
                if (raw instanceof java.sql.Date date) {
                    yield date.toLocalDate();
                }
                throw unsupportedValue(column, raw);
            }
            case TIME -> {
                if (raw instanceof LocalTime time) {
                    yield time;
                }
                if (raw instanceof java.sql.Time time) {
                    yield time.toLocalTime();
                }
                throw unsupportedValue(column, raw);
            }
            case TIMESTAMP -> {
                if (raw instanceof LocalDateTime dateTime) {
                    yield dateTime;
                }
                if (raw instanceof java.sql.Timestamp timestamp) {
                    yield timestamp.toLocalDateTime();
                }
                throw unsupportedValue(column, raw);
            }
            case TIMESTAMPTZ -> {
                if (raw instanceof OffsetDateTime offset) {
                    yield offset.toInstant();
                }
                if (raw instanceof ZonedDateTime zoned) {
                    yield zoned.toInstant();
                }
                if (raw instanceof Instant instant) {
                    yield instant;
                }
                if (raw instanceof java.sql.Timestamp timestamp) {
                    yield timestamp.toInstant();
                }
                throw unsupportedValue(column, raw);
            }
            case UUID -> {
                if (raw instanceof UUID uuid) {
                    yield uuid;
                }
                if (raw instanceof byte[] bytes && bytes.length == 16) {
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                    yield new UUID(buffer.getLong(), buffer.getLong());
                }
                try {
                    yield UUID.fromString(raw.toString());
                } catch (IllegalArgumentException e) {
                    throw unsupportedValue(column, raw);
                }
            }
            case REAL -> {
                if (raw instanceof Number number) {
                    yield number.floatValue();
                }
                throw unsupportedValue(column, raw);
            }
            case DOUBLE -> {
                if (raw instanceof Number number) {
                    yield number.doubleValue();
                }
                throw unsupportedValue(column, raw);
            }
            case JSON -> {
                String text = null;
                try {
                    text = result.getString(index);
                } catch (SQLException ignored) {
                    // Fall back to the object representation below.
                }
                if (text == null) {
                    if (raw instanceof byte[] bytes) {
                        text = new String(bytes, StandardCharsets.UTF_8);
                    } else if (raw instanceof String string) {
                        text = string;
                    } else {
                        text = raw.toString();
                    }
                }
                yield canonicalJson(text, column);
            }
        };
    }

    private static CutoverException unsupportedValue(ColumnMeta column, Object raw) {
        return new CutoverException("column " + column.name() + " returned unsupported JDBC value "
                + "type " + raw.getClass().getName());
    }

    private static String canonicalJson(String text, ColumnMeta column) {
        try {
            JsonNode node = CANONICAL_JSON.readTree(text);
            if (node == null) {
                throw new CutoverException("column " + column.name() + " contains empty JSON");
            }
            return CANONICAL_JSON.writeValueAsString(normalizeJson(node));
        } catch (IOException e) {
            throw new CutoverException("column " + column.name() + " contains invalid JSON");
        }
    }

    private static JsonNode normalizeJson(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode object = CANONICAL_JSON.createObjectNode();
            for (String name : names) {
                object.set(name, normalizeJson(node.get(name)));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = CANONICAL_JSON.createArrayNode();
            for (JsonNode element : node) {
                array.add(normalizeJson(element));
            }
            return array;
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            if (value.scale() < 0) {
                value = value.setScale(0);
            }
            return CANONICAL_JSON.getNodeFactory().numberNode(value);
        }
        return node;
    }

    private static void bind(PreparedStatement statement, int index, ColumnMeta column,
                             Object value) throws SQLException {
        if (value == null) {
            statement.setNull(index, nullJdbcType(column.type()));
            return;
        }
        switch (column.type()) {
            case TEXT -> statement.setString(index, (String) value);
            case DECIMAL -> statement.setBigDecimal(index, (BigDecimal) value);
            case INTEGER -> {
                long number = (Long) value;
                if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                    throw new CutoverException("integer value out of range for column "
                            + column.name());
                }
                statement.setInt(index, (int) number);
            }
            case BIGINT -> statement.setLong(index, (Long) value);
            case BOOLEAN -> statement.setBoolean(index, (Boolean) value);
            case BINARY -> statement.setBytes(index, (byte[]) value);
            case DATE -> statement.setObject(index, (LocalDate) value);
            case TIME -> statement.setObject(index, (LocalTime) value);
            case TIMESTAMP -> statement.setObject(index, (LocalDateTime) value);
            case TIMESTAMPTZ -> statement.setObject(index,
                    OffsetDateTime.ofInstant((Instant) value, ZoneOffset.UTC));
            case UUID -> statement.setObject(index, (UUID) value);
            case REAL -> statement.setFloat(index, (Float) value);
            case DOUBLE -> statement.setDouble(index, (Double) value);
            case JSON -> statement.setString(index, (String) value);
            default -> throw new CutoverException("unsupported insert type " + column.type());
        }
    }

    private static int nullJdbcType(LogicalType type) {
        return switch (type) {
            case TEXT -> Types.VARCHAR;
            case JSON -> Types.OTHER;
            case DECIMAL -> Types.NUMERIC;
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case BOOLEAN -> Types.BOOLEAN;
            case BINARY -> Types.VARBINARY;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMESTAMP -> Types.TIMESTAMP;
            case TIMESTAMPTZ -> Types.TIMESTAMP_WITH_TIMEZONE;
            case UUID -> Types.OTHER;
            case REAL -> Types.REAL;
            case DOUBLE -> Types.DOUBLE;
        };
    }

    /* --------------------------------------------------------------------- */
    /* Row streaming, verification, and foreign keys                         */
    /* --------------------------------------------------------------------- */

    private static long countRows(Connection connection, TableMeta table) {
        try {
            return singleLong(connection, "SELECT COUNT(*) FROM " + table.qualified());
        } catch (SQLException e) {
            throw sqlFailure("count source table " + table.name(), e);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement.setFetchSize(FETCH_SIZE);
        return statement;
    }

    private static TableResult copyTable(TablePlan plan, Connection source, Connection target) {
        TableDigest sourceDigest = new TableDigest(plan.source);
        long sourceRows = 0;
        try (PreparedStatement read = prepare(source, plan.sourceSelect);
             PreparedStatement write = target.prepareStatement(plan.insert)) {
            try (ResultSet result = read.executeQuery()) {
                while (result.next()) {
                    sourceDigest.row();
                    for (int i = 0; i < plan.source.columns().size(); i++) {
                        ColumnMeta sourceColumn = plan.source.columns().get(i);
                        ColumnMeta targetColumn = plan.target.columns().get(i);
                        Object value;
                        try {
                            value = readCanonical(result, i + 1, sourceColumn);
                        } catch (SQLException e) {
                            throw sqlFailure("read source table " + plan.name + " column "
                                    + sourceColumn.name(), e);
                        }
                        sourceDigest.value(sourceColumn, value);
                        try {
                            bind(write, i + 1, targetColumn, value);
                        } catch (SQLException e) {
                            throw sqlFailure("bind target table " + plan.name + " column "
                                    + targetColumn.name(), e);
                        }
                    }
                    write.addBatch();
                    sourceRows++;
                    if (sourceRows % BATCH_SIZE == 0) {
                        try {
                            executeBatch(write, plan.name);
                        } catch (SQLException e) {
                            throw sqlFailure("insert batch for table " + plan.name, e);
                        }
                    }
                }
            }
            try {
                executeBatch(write, plan.name);
            } catch (SQLException e) {
                throw sqlFailure("insert final batch for table " + plan.name, e);
            }
        } catch (SQLException e) {
            throw sqlFailure("stream table " + plan.name, e);
        }

        byte[] sourceHash = sourceDigest.finish(sourceRows);
        TableDigest targetDigest = new TableDigest(plan.target);
        long targetRows = 0;
        try (PreparedStatement read = prepare(target, plan.targetSelect);
             ResultSet result = read.executeQuery()) {
            while (result.next()) {
                targetDigest.row();
                for (int i = 0; i < plan.target.columns().size(); i++) {
                    ColumnMeta column = plan.target.columns().get(i);
                    Object value;
                    try {
                        value = readCanonical(result, i + 1, column);
                    } catch (SQLException e) {
                        throw sqlFailure("verify target table " + plan.name + " column "
                                + column.name(), e);
                    }
                    targetDigest.value(column, value);
                }
                targetRows++;
            }
        } catch (SQLException e) {
            throw sqlFailure("verify target table " + plan.name, e);
        }
        byte[] targetHash = targetDigest.finish(targetRows);
        if (sourceRows != targetRows) {
            throw new CutoverException("row count mismatch for table " + plan.name + ": source="
                    + sourceRows + " target=" + targetRows);
        }
        if (!MessageDigest.isEqual(sourceHash, targetHash)) {
            throw new CutoverException("semantic digest mismatch for table " + plan.name);
        }
        return new TableResult(plan.name, sourceRows, targetRows, sourceHash, targetHash);
    }

    private static void executeBatch(PreparedStatement statement, String table)
            throws SQLException {
        int[] counts = statement.executeBatch();
        for (int count : counts) {
            if (count == Statement.EXECUTE_FAILED) {
                throw new SQLException("batch update failed for table " + table);
            }
        }
        statement.clearBatch();
    }

    private static long deleteAll(Connection target, Map<String, TableMeta> targetTables) {
        long deleted = 0;
        for (TableMeta table : targetTables.values()) {
            try {
                deleted += executeUpdate(target, "DELETE FROM " + table.qualified());
            } catch (SQLException e) {
                throw sqlFailure("delete target migration seed rows from table " + table.name(),
                        e);
            }
        }
        return deleted;
    }

    private static List<ForeignKey> readForeignKeys(Connection target) throws SQLException {
        List<ForeignKey> keys = new ArrayList<>();
        String sql = "SELECT c.conname, t.relname, pg_get_constraintdef(c.oid), "
                + "c.convalidated FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid=c.conrelid "
                + "JOIN pg_namespace n ON n.oid=t.relnamespace "
                + "WHERE c.contype='f' AND n.nspname='public' "
                + "ORDER BY t.relname, c.conname";
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                keys.add(new ForeignKey(result.getString(2), result.getString(1),
                        result.getString(3), result.getBoolean(4)));
            }
        } catch (SQLException e) {
            throw sqlFailure("capture target foreign keys", e);
        }
        return keys;
    }

    private static void dropForeignKeys(Connection target, List<ForeignKey> keys) {
        for (ForeignKey key : keys) {
            String sql = "ALTER TABLE " + quote("public") + "." + quote(key.table())
                    + " DROP CONSTRAINT " + quote(key.name());
            try {
                execute(target, sql);
            } catch (SQLException e) {
                throw sqlFailure("drop target foreign key " + key.name(), e);
            }
        }
    }

    private static void restoreForeignKeys(Connection target, List<ForeignKey> keys) {
        for (ForeignKey key : keys) {
            String sql = "ALTER TABLE " + quote("public") + "." + quote(key.table())
                    + " ADD CONSTRAINT " + quote(key.name()) + " " + key.definition();
            try {
                execute(target, sql);
            } catch (SQLException e) {
                throw sqlFailure("restore target foreign key " + key.name(), e);
            }
        }
        for (ForeignKey key : keys) {
            String sql = "ALTER TABLE " + quote("public") + "." + quote(key.table())
                    + " VALIDATE CONSTRAINT " + quote(key.name());
            try {
                execute(target, sql);
            } catch (SQLException e) {
                throw sqlFailure("validate target foreign key " + key.name(), e);
            }
        }
    }

    private static void verifyForeignKeys(Connection target, List<ForeignKey> expected)
            throws SQLException {
        String sql = "SELECT c.conname, t.relname, c.convalidated FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid=c.conrelid "
                + "JOIN pg_namespace n ON n.oid=t.relnamespace "
                + "WHERE c.contype='f' AND n.nspname='public'";
        Map<String, Boolean> actual = new TreeMap<>();
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                actual.put(result.getString(2) + "\u0000" + result.getString(1),
                        result.getBoolean(3));
            }
        } catch (SQLException e) {
            throw sqlFailure("verify target foreign keys", e);
        }
        for (ForeignKey key : expected) {
            String identity = key.table() + "\u0000" + key.name();
            Boolean validated = actual.remove(identity);
            if (validated == null) {
                throw new CutoverException("target foreign key disappeared: " + key.name());
            }
            if (!validated) {
                throw new CutoverException("target foreign key is not validated: " + key.name());
            }
        }
        if (!actual.isEmpty()) {
            throw new CutoverException("target has unexpected foreign keys: " + actual.keySet());
        }
    }

    private static String flywayMessage(RuntimeException failure) {
        String message = failure.getClass().getSimpleName();
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            message += ": " + failure.getMessage();
        }
        return scrub(message, null);
    }

    private static CutoverException sqlFailure(String context, SQLException failure) {
        return new CutoverException(context + " failed: SQLState=" + safe(failure.getSQLState())
                + ", errorCode=" + failure.getErrorCode());
    }
}
