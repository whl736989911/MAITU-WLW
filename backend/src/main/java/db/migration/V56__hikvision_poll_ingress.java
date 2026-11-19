package db.migration;

import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Extend the existing check in place: no rebuild/copy of the large immutable message table. */
public class V56__hikvision_poll_ingress extends BaseJavaMigration {
    private record Check(String name,String expression) { }
    @Override public void migrate(Context context)throws Exception {
        var connection=context.getConnection();
        var checks=new ArrayList<Check>();
        // PostgreSQL information_schema also projects NOT NULL as a CHECK.
        String sql=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")
            ? "SELECT c.conname,pg_get_expr(c.conbin,c.conrelid) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE t.relname='legacy_ingest_messages' AND n.nspname=? AND c.contype='c'"
            : "SELECT t.constraint_name,c.check_clause FROM information_schema.table_constraints t JOIN information_schema.check_constraints c ON c.constraint_catalog=t.constraint_catalog AND c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name WHERE LOWER(t.table_name)='legacy_ingest_messages' AND t.table_schema=? AND t.constraint_type='CHECK'";
        try(var query=connection.prepareStatement(sql)) {
            query.setString(1,connection.getSchema());
            try(var rows=query.executeQuery()) {
                while(rows.next()) {
                    String clause=rows.getString(2);
                    if(clause!=null&&java.util.regex.Pattern.compile("(?i)\\bingress\\b").matcher(clause).find())checks.add(new Check(rows.getString(1),clause));
                }
            }
        }
        if(checks.size()!=1)throw new IllegalStateException("Expected one message ingress check; refusing an ambiguous migration");
        var check=checks.getFirst();if(check.expression().contains("HIKVISION_POLL"))return;
        String identifier="\""+check.name().replace("\"","\"\"")+"\"";
        try(var statement=connection.createStatement()) {
            statement.execute("ALTER TABLE legacy_ingest_messages DROP CONSTRAINT "+identifier);
            statement.execute("ALTER TABLE legacy_ingest_messages ADD CONSTRAINT ck_legacy_ingress_real_source CHECK (("+check.expression()+") OR ingress='HIKVISION_POLL')");
        }
    }
}
