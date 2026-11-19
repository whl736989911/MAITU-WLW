package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve the raw payload and its actual ingress independently of source occurrence identity. */
public class V95__hikvision_raw_transport_identity extends BaseJavaMigration {
    private static final List<String> PREVIOUS_KEY = List.of("stream_kind", "external_key", "payload_sha256");

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        String previousConstraint = previousConstraint(connection);
        try (var statement = connection.createStatement()) {
            statement.execute("""
                ALTER TABLE hikvision_raw_records ADD CONSTRAINT uq_hikvision_raw_transport
                    UNIQUE (stream_kind, external_key, payload_sha256, ingress)
                """);
            statement.execute("ALTER TABLE hikvision_raw_records DROP CONSTRAINT \""
                + previousConstraint.replace("\"", "\"\"") + "\"");
        }
    }

    private String previousConstraint(Connection connection) throws SQLException {
        // V55 used an unnamed constraint; H2 and PostgreSQL give it different names.
        Map<String, List<String>> keys = new LinkedHashMap<>();
        try (var query = connection.prepareStatement("""
            SELECT tc.constraint_name, k.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage k
              ON k.constraint_catalog=tc.constraint_catalog AND k.constraint_schema=tc.constraint_schema
                AND k.constraint_name=tc.constraint_name AND k.table_catalog=tc.table_catalog
                AND k.table_schema=tc.table_schema AND k.table_name=tc.table_name
            WHERE tc.table_schema=? AND LOWER(tc.table_name)='hikvision_raw_records' AND tc.constraint_type='UNIQUE'
            ORDER BY tc.constraint_name, k.ordinal_position
            """)) {
            query.setString(1, connection.getSchema());
            try (var rows = query.executeQuery()) {
                while (rows.next()) keys.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>()).add(rows.getString(2));
            }
        }
        var matches = keys.entrySet().stream().filter(key -> PREVIOUS_KEY.equals(key.getValue())).toList();
        if (matches.size() != 1) throw new IllegalStateException("Expected one V55 raw Hikvision payload UNIQUE constraint");
        return matches.getFirst().getKey();
    }
}
