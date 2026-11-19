package db.migration;

import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve immutable device identifiers while allowing a transferred asset's new tenant instance to reuse its EUI. */
public class V64__device_eui_history_slots extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        try(var statement=connection.createStatement()) {
            statement.execute("ALTER TABLE devices ADD COLUMN identity_slot VARCHAR(80) NOT NULL DEFAULT 'ACTIVE'");
        }
        var constraints=new ArrayList<String>();
        try(var query=connection.prepareStatement("SELECT tc.constraint_name FROM information_schema.table_constraints tc WHERE LOWER(tc.table_name)='devices' AND tc.table_schema=? AND tc.constraint_type='UNIQUE'")) {
            query.setString(1,connection.getSchema());
            try(var rows=query.executeQuery()) {
                while(rows.next()) {
                    String name=rows.getString(1);
                    try(var columns=connection.prepareStatement("SELECT column_name FROM information_schema.key_column_usage WHERE constraint_schema=? AND constraint_name=? AND LOWER(table_name)='devices' ORDER BY ordinal_position")) {
                        columns.setString(1,connection.getSchema());columns.setString(2,name);
                        try(var keys=columns.executeQuery()) {
                            if(keys.next()&&"device_eui".equalsIgnoreCase(keys.getString(1))&&!keys.next())constraints.add(name);
                        }
                    }
                }
            }
        }
        if(constraints.size()!=1)throw new IllegalStateException("Expected exactly one single-column devices.device_eui UNIQUE constraint; refusing ambiguous migration");
        String identifier="\""+constraints.getFirst().replace("\"","\"\"")+"\"";
        try(var statement=connection.createStatement()) {
            statement.execute("ALTER TABLE devices DROP CONSTRAINT "+identifier);
            statement.execute("UPDATE devices SET identity_slot='HISTORY:' || id WHERE archived_at IS NOT NULL");
            statement.execute("ALTER TABLE devices ADD CONSTRAINT ck_device_identity_slot CHECK ((archived_at IS NULL AND identity_slot='ACTIVE') OR (archived_at IS NOT NULL AND identity_slot='HISTORY:' || id))");
            statement.execute("ALTER TABLE devices ADD CONSTRAINT uq_devices_eui_identity_slot UNIQUE(device_eui,identity_slot)");
        }
    }
}
