package db.migration;

import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Force clients holding pre-cutover cursors to establish a new baseline without rewriting history. */
public class V57__real_alarm_stream_epochs extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        try(var query=connection.prepareStatement("SELECT tenant_id FROM alarm_change_heads ORDER BY tenant_id");
            var rows=query.executeQuery();
            var update=connection.prepareStatement("UPDATE alarm_change_heads SET stream_id=? WHERE tenant_id=?")) {
            while(rows.next()) {
                update.setString(1,UUID.randomUUID().toString());
                update.setString(2,rows.getString(1));
                if(update.executeUpdate()!=1)throw new IllegalStateException("Alarm stream epoch was not rotated");
            }
        }
    }
}
