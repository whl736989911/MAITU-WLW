package com.fireplatform.notification;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.*;
import com.fireplatform.transport.WechatPayV2Client;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily WeChat Pay bill reconciliation for local platform orders only.
 *
 * <p>The downloaded bill is persisted encrypted and immutable together with its digest; differences are
 * insert-only observations. Nothing here credits, refunds, cancels or re-prices an order: a bill row that
 * shows a payment for a locally unpaid order is reported as {@code BILL_PAID_LOCAL_UNPAID} and must be
 * resolved through the explicit, signature-verified order query action. Rows that do not belong to a
 * local order are counted, never imported, so shared-merchant or legacy external customers are not exposed.</p>
 */
@Service
public class PaymentReconciliationService {
    public record RunView(String id, String billDate, String status, String requestedBy,
                          long localOrders, long billRows, long matchedRows, long externalRows,
                          long differenceCount, long malformedRows, String errorCode, String errorMessage,
                          String billSha256, OffsetDateTime createdAt, OffsetDateTime finishedAt) { }
    public record Difference(String kind, String orderId, String outTradeNo, String transactionIdMasked,
                             String localStatus, Long localAmountCents, Long billAmountCents, String billTradeState, String detail) { }
    public record RunDetail(RunView run, List<Difference> differences) { }

    private record BillRow(int line, String outTradeNo, String transactionId, String tradeState, long amountCents,
                           String merchantId, String appId, String currency) { }
    private record LocalOrder(String id, String outTradeNo, String status, String transactionId, Long paidAmountCents, long amountCents) { }
    private record Parsed(List<BillRow> rows, int malformed, Map<String, BillRow> byOrder, List<Difference> structural) { }
    private record Claim(RunDetail replay, String runId, String billDate, String merchantId, WechatPayV2Client.Credentials credentials, String hash) { }

    private static final DateTimeFormatter BILL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final PaymentSecretStorage secrets;
    private final PaymentConfigService config;
    private final WechatPayV2Client client;
    private final DatabaseTime time;
    private final TransactionTemplate transactions;

    public PaymentReconciliationService(JdbcTemplate jdbc, ManagementGuard guard, IdempotencyStore idem, AuditService audit,
            PaymentSecretStorage secrets, PaymentConfigService config, WechatPayV2Client client, DatabaseTime time,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.guard = guard; this.idem = idem; this.audit = audit; this.secrets = secrets;
        this.config = config; this.client = client; this.time = time; this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly=true)
    public Page<RunView> list(int page, Integer size) {
        var p = Pagination.of(page, size, 20);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_runs", Long.class);
        var items = jdbc.query("SELECT * FROM payment_reconciliation_runs ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs, n) -> runView(rs), p.size(), p.offset());
        return new Page<>(items, total, p.page(), p.size());
    }

    @Transactional(readOnly=true)
    public RunDetail detail(String id) {
        var rows = jdbc.query("SELECT * FROM payment_reconciliation_runs WHERE id=?", (rs, n) -> runView(rs), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return new RunDetail(rows.getFirst(), differences(id));
    }

    public RunDetail run(AuthService.Identity actor, String key, PaymentReconciliationInputs.Run input, String request) {
        Claim claim = transactions.execute(status -> {
            guard.begin(actor, "payments:write", null, "management");
            idem.validateKey(key);
            String billDate = billDate(input.billDate());
            String hash = idem.fingerprint(List.of(billDate));
            var replay = idem.replay(actor.user().id(), "payment-reconciliation-run", key, hash, RunDetail.class);
            if (replay != null) return new Claim(replay, null, null, null, null, null);
            if (!secrets.ready())
                throw new ApiError(503, "PAYMENT_SECRET_STORAGE_UNAVAILABLE", "支付密钥存储未配置，不能保存对账原始文件");
            String id = UUID.randomUUID().toString();
            String merchantId = config.reportingMerchantId();
            var existingRun = jdbc.query("SELECT id FROM payment_reconciliation_runs WHERE requested_by=? AND idem_key=?",
                (rs, n) -> rs.getString(1), actor.user().id(), key);
            if (!existingRun.isEmpty()) return new Claim(detail(existingRun.getFirst()), null, null, null, null, null);
            jdbc.update("INSERT INTO payment_reconciliation_runs(id,bill_date,status,requested_by,idem_key,merchant_mch_id,created_at) VALUES (?,?,'RUNNING',?,?,?,?)",
                id, billDate, actor.user().id(), key, merchantId, time.now());
            audit.record(actor.user().id(), null, "PAYMENT_RECONCILIATION_RUN", id, "RUNNING", request);
            return new Claim(null, id, billDate, merchantId, config.reportingCredentials(), hash);
        });
        if (claim.replay() != null) return claim.replay();
        if (claim.runId() == null)
            throw ApiError.conflict("RECONCILIATION_IN_PROGRESS", "相同幂等键的对账正在处理中，请刷新对账记录");
        return execute(claim.runId(), claim.billDate(), claim.merchantId(), claim.credentials(), actor.user().id(), key, claim.hash(), request);
    }

    /** Automatic daily run; disabled unless the operator enabled reconciliation in the page configuration. */
    public RunDetail systemRun(String billDate) {
        Claim claim = transactions.execute(status -> {
            String id = UUID.randomUUID().toString();
            String merchantId = config.reportingMerchantId();
            jdbc.update("INSERT INTO payment_reconciliation_runs(id,bill_date,status,requested_by,idem_key,merchant_mch_id,created_at) VALUES (?,?,'RUNNING',NULL,NULL,?,?)",
                id, billDate, merchantId, time.now());
            return new Claim(null, id, billDate, merchantId, config.reportingCredentials(), null);
        });
        return execute(claim.runId(), claim.billDate(), claim.merchantId(), claim.credentials(), null, null, null, null);
    }

    private RunDetail execute(String runId, String billDate, String merchantId, WechatPayV2Client.Credentials credentials,
                              String actor, String idemKey, String hash, String request) {
        if (credentials == null) return finalizeFailure(runId, "PAYMENT_NOT_CONFIGURED", "未配置微信支付商户凭据，不能下载对账单", actor, idemKey, hash, request);
        var bill = client.downloadBill(credentials, billDate);
        if (!bill.success()) return finalizeFailure(runId, truncate(bill.code(), 64), truncate(bill.message(), 300), actor, idemKey, hash, request);
        var local = localOrders(billDate);
        Parsed parsed = parseBill(bill.content(), merchantId);
        return transactions.execute(status -> persist(runId, billDate, bill.content(), parsed, local, actor, idemKey, hash, request));
    }

    private RunDetail persist(String runId, String billDate, String content, Parsed parsed, Map<String, LocalOrder> local,
                              String actor, String idemKey, String hash, String request) {
        long matched = local.keySet().stream().filter(parsed.byOrder()::containsKey).count();
        long external = parsed.rows().stream().filter(row -> !local.containsKey(row.outTradeNo())).count();
        List<Difference> differences = parsed.structural();
        Map<String, Integer> transactionUse = new HashMap<>();
        for (var row : parsed.rows()) transactionUse.merge(row.transactionId(), 1, Integer::sum);
        for (var entry : local.entrySet()) {
            var order = entry.getValue();
            var row = parsed.byOrder().get(entry.getKey());
            if (row == null) {
                if ("PAID".equals(order.status()))
                    differences.add(difference("MISSING_BILL_ROW", order, null, "本地已支付订单未出现在当日成功交易对账单中"));
                continue;
            }
            if (transactionUse.getOrDefault(row.transactionId(), 0) > 1)
                differences.add(difference("DUPLICATE_TRANSACTION", order, row, "同一微信交易号在对账单中出现多次"));
            if (!"CNY".equals(row.currency()))
                differences.add(difference("CURRENCY_MISMATCH", order, row, "对账单币种不是 CNY"));
            if ("PAID".equals(order.status())) {
                long expected = order.paidAmountCents() == null ? order.amountCents() : order.paidAmountCents();
                if (!"SUCCESS".equals(row.tradeState()))
                    differences.add(difference("STATE_MISMATCH", order, row, "本地已支付但对账单交易状态不是 SUCCESS"));
                if (row.amountCents() != expected)
                    differences.add(difference("AMOUNT_MISMATCH", order, row, "本地入账金额与对账单订单金额不一致"));
                if (order.transactionId() != null && !order.transactionId().equals(row.transactionId()))
                    differences.add(difference("TRANSACTION_MISMATCH", order, row, "本地交易号与对账单微信订单号不一致"));
            } else if ("SUCCESS".equals(row.tradeState())) {
                differences.add(difference("BILL_PAID_LOCAL_UNPAID", order, row, "对账单显示已支付但本地未入账；需要显式查单核验，不会自动入账"));
            }
        }
        for (var difference : differences) {
            jdbc.update("INSERT INTO payment_reconciliation_differences(id,run_id,kind,order_id,out_trade_no,transaction_id,local_status,local_amount_cents,bill_amount_cents,bill_trade_state,detail,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), runId, difference.kind(), difference.orderId(), difference.outTradeNo(),
                difference.transactionIdMasked(), difference.localStatus(), difference.localAmountCents(),
                difference.billAmountCents(), difference.billTradeState(), truncate(difference.detail(), 300));
        }
        var protectedBill = secrets.protect(content);
        int updated = jdbc.update("UPDATE payment_reconciliation_runs SET status='COMPLETED',bill_sha256=?,bill_cipher=?,bill_nonce=?,local_orders=?,bill_rows=?,matched_rows=?,external_rows=?,difference_count=?,malformed_rows=?,finished_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",
            Digests.sha256(content), protectedBill.cipher(), protectedBill.nonce(), (long) local.size(), (long) parsed.rows().size(),
            matched, external, (long) differences.size(), (long) parsed.malformed(), runId);
        RunDetail detail = detail(runId);
        if (updated == 1 && actor != null) audit.record(actor, null, "PAYMENT_RECONCILIATION_RUN", runId, "COMPLETED", request == null ? "BILL:" + billDate : request);
        if (idemKey != null && hash != null) idem.save(actor, "payment-reconciliation-run", idemKey, hash, detail);
        return detail;
    }

    private RunDetail finalizeFailure(String runId, String code, String message, String actor, String idemKey, String hash, String request) {
        return transactions.execute(status -> {
            jdbc.update("UPDATE payment_reconciliation_runs SET status='FAILED',error_code=?,error_message=?,finished_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'",
                code, message, runId);
            RunDetail detail = detail(runId);
            if (actor != null) audit.record(actor, null, "PAYMENT_RECONCILIATION_RUN", runId, "FAILED", request == null ? "BILL:RUN" : request);
            if (idemKey != null && hash != null) idem.save(actor, "payment-reconciliation-run", idemKey, hash, detail);
            return detail;
        });
    }

    private Map<String, LocalOrder> localOrders(String billDate) {
        LocalDate day = LocalDate.parse(billDate, BILL_DATE);
        OffsetDateTime start = day.atStartOfDay(BEIJING).toOffsetDateTime();
        OffsetDateTime end = day.plusDays(1).atStartOfDay(BEIJING).toOffsetDateTime();
        var list = jdbc.query("SELECT id,out_trade_no,status,transaction_id,paid_amount_cents,amount_cents FROM notification_resource_orders WHERE out_trade_no IS NOT NULL AND ((paid_at>=? AND paid_at<?) OR (paid_at IS NULL AND created_at>=? AND created_at<?))",
            (rs, n) -> new LocalOrder(rs.getString("id"), rs.getString("out_trade_no"), rs.getString("status"),
                rs.getString("transaction_id"), rs.getObject("paid_amount_cents", Long.class), rs.getLong("amount_cents")),
            start, end, start, end);
        var map = new LinkedHashMap<String, LocalOrder>();
        for (var order : list) map.put(order.outTradeNo(), order);
        return map;
    }

    private static Parsed parseBill(String content, String expectedMerchantId) {
        List<BillRow> rows = new ArrayList<>();
        List<Difference> structural = new ArrayList<>();
        Map<String, BillRow> byOrder = new LinkedHashMap<>();
        Set<String> transactionIds = new HashSet<>();
        int malformed = 0;
        boolean summaryValues = false;
        if (content == null) return new Parsed(rows, malformed, byOrder, structural);
        String normalized = content.startsWith("\uFEFF") ? content.substring(1) : content;
        String[] lines = normalized.split("\\r?\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty()) continue;
            String data = line.startsWith("`") ? line.substring(1) : line;
            if (data.startsWith("交易时间")) continue;
            if (data.startsWith("总交易单数")) { summaryValues = true; continue; }
            if (summaryValues) { summaryValues = false; continue; }
            int lineNumber = index + 1;
            String[] fields = data.split(",", -1);
            if (fields.length < 27) {
                malformed++;
                structural.add(new Difference("MALFORMED_ROW", null, null, null, null, null, null, null, "第" + lineNumber + "行列数不足"));
                continue;
            }
            String outTradeNo = fields[6].trim(), transactionId = fields[5].trim(), tradeState = fields[9].trim();
            String merchantId = fields[2].trim(), appId = fields[1].trim(), currency = fields[11].trim();
            Long cents = yuanToCents(fields[24]);
            if (outTradeNo.isEmpty() || transactionId.isEmpty() || tradeState.isEmpty() || cents == null) {
                malformed++;
                structural.add(new Difference("MALFORMED_ROW", null, outTradeNo.isEmpty() ? null : outTradeNo, null, null, null, null,
                    tradeState.isEmpty() ? null : tradeState, "第" + lineNumber + "行关键字段缺失或金额格式无效"));
                continue;
            }
            var row = new BillRow(lineNumber, outTradeNo, transactionId, tradeState, cents, merchantId, appId, currency);
            rows.add(row);
            if (expectedMerchantId != null && !expectedMerchantId.equals(merchantId))
                structural.add(new Difference("MERCHANT_MISMATCH", null, outTradeNo, mask(transactionId), null, null, cents, tradeState,
                    "第" + lineNumber + "行商户号与当前支付配置不一致"));
            if (byOrder.containsKey(outTradeNo)) {
                structural.add(new Difference("DUPLICATE_BILL_ORDER", null, outTradeNo, mask(transactionId), null, null, cents, tradeState,
                    "第" + lineNumber + "行商户订单号在当日对账单中重复出现"));
                continue;
            }
            byOrder.put(outTradeNo, row);
            if (!transactionIds.add(transactionId))
                structural.add(new Difference("DUPLICATE_TRANSACTION", null, outTradeNo, mask(transactionId), null, null, cents, tradeState,
                    "第" + lineNumber + "行微信交易号在当日对账单中重复出现"));
        }
        return new Parsed(rows, malformed, byOrder, structural);
    }

    private static Difference difference(String kind, LocalOrder order, BillRow row, String detail) {
        Long localAmount = order.paidAmountCents() == null ? order.amountCents() : order.paidAmountCents();
        return new Difference(kind, order.id(), order.outTradeNo(), row == null ? null : mask(row.transactionId()),
            order.status(), localAmount, row == null ? null : row.amountCents(),
            row == null ? null : row.tradeState(), detail);
    }

    private static Long yuanToCents(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal amount = new BigDecimal(value.trim());
            if (amount.signum() < 0 || amount.scale() > 2 || amount.precision() > 18) return null;
            return amount.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }

    private List<Difference> differences(String runId) {
        return jdbc.query("SELECT kind,order_id,out_trade_no,transaction_id,local_status,local_amount_cents,bill_amount_cents,bill_trade_state,detail FROM payment_reconciliation_differences WHERE run_id=? ORDER BY kind,out_trade_no,id",
            (rs, n) -> new Difference(rs.getString("kind"), rs.getString("order_id"), rs.getString("out_trade_no"),
                rs.getString("transaction_id"), rs.getString("local_status"), rs.getObject("local_amount_cents", Long.class),
                rs.getObject("bill_amount_cents", Long.class), rs.getString("bill_trade_state"), rs.getString("detail")), runId);
    }

    private static RunView runView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunView(rs.getString("id"), rs.getString("bill_date"), rs.getString("status"), rs.getString("requested_by"),
            rs.getLong("local_orders"), rs.getLong("bill_rows"), rs.getLong("matched_rows"), rs.getLong("external_rows"),
            rs.getLong("difference_count"), rs.getLong("malformed_rows"), rs.getString("error_code"), rs.getString("error_message"),
            rs.getString("bill_sha256"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class));
    }

    private static String billDate(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.matches("\\d{8}")) throw ApiError.bad("对账日期必须为 yyyyMMdd");
        LocalDate day;
        try { day = LocalDate.parse(trimmed, BILL_DATE); } catch (java.time.format.DateTimeParseException invalid) { throw ApiError.bad("对账日期无效"); }
        LocalDate today = LocalDate.now(BEIJING);
        if (!day.isBefore(today)) throw ApiError.bad("只能对昨日或更早的账单对账");
        if (day.isBefore(today.minusDays(95))) throw ApiError.bad("微信支付对账单只保留约三个月");
        return trimmed;
    }

    private static String mask(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) return null;
        return transactionId.length() <= 4 ? "****" : "****" + transactionId.substring(transactionId.length() - 4);
    }
    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
