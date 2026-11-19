package com.fireplatform.audit;

import com.fireplatform.shared.ScopeFilter;
import com.fireplatform.shared.TransactionRequirements;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogExport {
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final JdbcTemplate jdbc;
    private final AuditRecordQueries queries;
    private final TransactionRequirements transactions;
    public AuditLogExport(JdbcTemplate jdbc, AuditRecordQueries queries, TransactionRequirements transactions) {
        this.jdbc = jdbc; this.queries = queries; this.transactions = transactions;
    }
    static void cell(Writer writer, String value) throws IOException {
        writer.write('"');
        if (value != null) {
            String stripped = value.stripLeading();
            if (!stripped.isEmpty() && "=+-@\t\r\n".indexOf(stripped.charAt(0)) >= 0 || !value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0) writer.write('\'');
            for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); if (c == '"') writer.write('"'); writer.write(c); }
        }
        writer.write('"');
    }
    private static void row(Writer writer, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) { if (i != 0) writer.write(','); cell(writer, values[i]); }
        writer.write("\r\n");
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void write(String actor, AuditRecordFilter filter, ScopeFilter.Selection selection, AuditLogSearch search, AuditLogSearch.Access access, Writer writer) throws IOException {
        transactions.repeatableReadOnly();
        var plan = queries.plan(actor, filter, selection, search, access);
        writer.write('\uFEFF');
        row(writer, "记录编号", "单位", "发生时间（UTC）", "功能模块", "操作", "结果", "用户名（按目录权限）", "操作者UUID", "目标UUID", "请求端标记");
        try {
            jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT " + AuditRecordProjection.COLUMNS + AuditLogSearch.columns(access)
                    + " FROM audit_log a JOIN tenants t ON t.id=a.tenant_id" + plan.where() + " ORDER BY a.occurred_at DESC,a.id ASC");
                statement.setFetchSize(256);
                for (int i = 0; i < plan.bindings().size(); i++) statement.setObject(i + 1, plan.bindings().get(i));
                return statement;
            }, (RowCallbackHandler) rs -> {
                var item = AuditRecordProjection.row(rs);
                try {
                    row(writer, item.id(), item.tenantName(), TIME.format(item.occurredAt()), rs.getString("log_module"),
                        item.action(), item.outcome(), rs.getString("log_actor_username"), item.actorRef(), item.targetRef(), rs.getString("log_platform"));
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
        writer.flush();
    }
}
