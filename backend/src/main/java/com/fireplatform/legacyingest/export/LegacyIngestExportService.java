package com.fireplatform.legacyingest.export;

import com.fireplatform.identity.AuthService;
import com.fireplatform.legacyingest.query.LegacyQueries;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.ScopeFilter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LegacyIngestExportService {
    /** target is TENANT (tenant_id required) or ALL_AUTHORIZED (tenant_id must be absent). */
    public record ExportRequest(String kind, String state, String platform, String run_id, Integer max_rows,
                                String target, String tenant_id, String reason) { }
    /** Same artifact shape as generic data-transfer downloads; the digest lets the browser verify bytes. */
    public record ExportFile(String kind, String filename, String media_type, String content_base64,
                             String content_sha256, int row_count) { }

    private static final int DEFAULT_MAX_ROWS = 5_000;
    private static final int MAX_ROWS = 10_000;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final List<String> MESSAGE_HEADER = List.of("id", "ingress", "vendor_platform", "vendor_device_id", "device_id", "state", "failure_code", "observed_at", "received_at", "duplicate_count", "payload_sha256");
    private static final List<String> RECONCILE_HEADER = List.of("run_id", "kind", "legacy_id", "new_id", "detail", "created_at");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LegacyIngestExportService.class);

    private final JdbcTemplate jdbc;
    private final LegacyQueries queries;
    private final Clock clock;

    @Autowired
    public LegacyIngestExportService(JdbcTemplate jdbc, LegacyQueries queries) {
        this(jdbc, queries, Clock.systemUTC());
    }

    LegacyIngestExportService(JdbcTemplate jdbc, LegacyQueries queries, Clock clock) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.clock = clock;
    }

    /**
     * The caller has already revalidated the actor, the explicit target and the current read scope.
     * Both branches reuse the exact predicates of the corresponding list query.
     */
    public ExportFile export(AuthService.Identity actor, String tenant, ScopeFilter.Selection selection, ExportRequest request) {
        if (request == null || request.kind() == null || request.kind().isBlank()) throw ApiError.bad("导出类型必填");
        int maxRows = request.max_rows() == null ? DEFAULT_MAX_ROWS : request.max_rows();
        if (maxRows < 1 || maxRows > MAX_ROWS) throw ApiError.bad("max_rows 必须在 1 到 " + MAX_ROWS + " 之间");
        log.info("Legacy ingest CSV export applies apostrophe escaping to formula-leading text cells");
        return switch (request.kind()) {
            case "MESSAGES" -> file("MESSAGES", MESSAGE_HEADER, messages(actor, tenant, selection, request, maxRows));
            case "RECONCILE" -> file("RECONCILE", RECONCILE_HEADER, reconcile(actor, tenant, selection, request, maxRows));
            default -> throw ApiError.bad("不支持的导出类型");
        };
    }

    private List<String[]> messages(AuthService.Identity actor, String tenant, ScopeFilter.Selection selection, ExportRequest request, int maxRows) {
        var filter = queries.messageFilter(actor.user().id(), tenant, selection);
        StringBuilder sql = new StringBuilder("SELECT id,ingress,vendor_platform,vendor_device_id,device_id,state,failure_code,observed_at,received_at,duplicate_count,payload_sha256")
            .append(filter.fromWhere());
        List<Object> args = new ArrayList<>(filter.bindings());
        if (request.state() != null && !request.state().isBlank()) { sql.append(" AND state=?"); args.add(request.state()); }
        if (request.platform() != null && !request.platform().isBlank()) { sql.append(" AND vendor_platform=?"); args.add(request.platform()); }
        sql.append(" ORDER BY received_at DESC,id LIMIT ?");
        args.add(maxRows);
        return jdbc.query(sql.toString(), (rs, row) -> {
            String[] values = new String[11];
            for (int i = 0; i < values.length; i++) values[i] = rs.getString(i + 1);
            return values;
        }, args.toArray());
    }

    private List<String[]> reconcile(AuthService.Identity actor, String tenant, ScopeFilter.Selection selection, ExportRequest request, int maxRows) {
        var filter = queries.differenceFilter(actor.user().id(), tenant, selection);
        StringBuilder sql = new StringBuilder("SELECT run_id,kind,legacy_id,new_id,detail,created_at").append(filter.fromWhere());
        List<Object> args = new ArrayList<>(filter.bindings());
        if (request.run_id() != null && !request.run_id().isBlank()) { sql.append(" AND run_id=?"); args.add(request.run_id()); }
        sql.append(" ORDER BY created_at DESC,id LIMIT ?");
        args.add(maxRows);
        return jdbc.query(sql.toString(), (rs, row) -> {
            String[] values = new String[6];
            for (int i = 0; i < values.length; i++) values[i] = rs.getString(i + 1);
            return values;
        }, args.toArray());
    }

    private ExportFile file(String kind, List<String> header, List<String[]> rows) {
        byte[] bytes = csv(header, rows);
        String timestamp = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).format(FILE_TIMESTAMP);
        String filename = "legacy-" + kind.toLowerCase(java.util.Locale.ROOT) + "-" + timestamp + "-"
            + UUID.randomUUID().toString().substring(0, 8) + ".csv";
        return new ExportFile(kind, filename, "text/csv;charset=utf-8", Base64.getEncoder().encodeToString(bytes),
            sha256(bytes), rows.size());
    }

    static byte[] csv(List<String> header, List<String[]> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0xEF); output.write(0xBB); output.write(0xBF);
        writeRow(output, header.toArray(String[]::new));
        for (String[] row : rows) writeRow(output, row);
        return output.toByteArray();
    }

    private static void writeRow(ByteArrayOutputStream output, String[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) output.write(',');
            String value = safeCell(values[i] == null ? "" : values[i]);
            output.write('"');
            byte[] encoded = value.replace("\"", "\"\"").getBytes(StandardCharsets.UTF_8);
            output.write(encoded, 0, encoded.length);
            output.write('"');
        }
        output.write('\r'); output.write('\n');
    }

    static String safeCell(String value) {
        int index = 0;
        while (index < value.length() && (value.charAt(index) == ' ' || value.charAt(index) == '\t' || value.charAt(index) == '\r' || value.charAt(index) == '\n')) index++;
        if (index == value.length()) return value;
        char first = value.charAt(index);
        if (first == '-') {
            String candidate = value.substring(index);
            if (candidate.matches("-\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")) return value;
        }
        if ("=+-@".indexOf(first) >= 0) return "'" + value;
        return value;
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
