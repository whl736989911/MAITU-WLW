package com.fireplatform.notification;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.transport.WechatPayV2Client;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WeChat Pay order lifecycle: initiation, verified callback credit, explicit query/close compensation.
 *
 * <p>Every official network call happens outside a database transaction. The local attempt is claimed
 * and persisted first, and the returned evidence is applied in a second transaction. A local timeout,
 * a communication error or an unverifiable response stays {@code UNKNOWN}; it never becomes a payment
 * failure, a merchant closure or a re-issued order. PAID credit is exactly-once because the provider
 * transaction identity is inserted into {@code payment_paid_transactions} before any balance change.</p>
 */
@Service
public class PaymentOrderService {
    public record PaymentState(String orderId, String status, String paymentState, String closeState, String queryState,
                               int queryAttempts, int closeAttempts, OffsetDateTime expiresAt, OffsetDateTime paidAt) { }
    public record ActionResult(String action, String outcome, String message, PaymentState order) { }
    public record CallbackResult(boolean acknowledged, String code, String message) { }

    private record OrderRow(String id, String tenantId, String outTradeNo, String accountId, String channel, long quantity, long amountCents,
                            String status, String paymentState, String transactionId, Long paidAmountCents,
                            OffsetDateTime createdAt, OffsetDateTime expiresAt, Long merchantRevision,
                            String merchantMchId, String merchantAppId, String closeState, String queryState) { }

    private static final String ORDER_COLUMNS = "id,tenant_id,out_trade_no,account_id,channel,quantity,amount_cents,status,payment_state,"
        + "transaction_id,paid_amount_cents,created_at,expires_at,merchant_config_revision,merchant_mch_id,merchant_app_id,close_state,query_state";

    private final JdbcTemplate jdbc;
    private final ManagementGuard guard;
    private final AuditService audit;
    private final PaymentConfigService config;
    private final WechatPayV2Client client;
    private final DatabaseTime time;
    private final TransactionTemplate transactions;

    public PaymentOrderService(JdbcTemplate jdbc, ManagementGuard guard, AuditService audit, PaymentConfigService config,
                               WechatPayV2Client client, DatabaseTime time, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.guard = guard; this.audit = audit; this.config = config; this.client = client; this.time = time;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ initiation

    /** Submit one locally created order to the provider exactly once, outside any open transaction. */
    public void initiate(String tenant, String orderId, String clientIp, String description) {
        var order = load(orderId, tenant);
        if (order == null || !"PENDING".equals(order.status()) || !"CREATED".equals(order.paymentState())
            || order.outTradeNo() == null) return;
        var credentials = config.credentialsForSnapshot(order.merchantRevision(), order.merchantMchId(), order.merchantAppId());
        if (credentials == null) return;
        Boolean claimed = transactions.execute(status -> jdbc.update(
            "UPDATE notification_resource_orders SET payment_state='IN_FLIGHT',payment_attempts=payment_attempts+1,last_payment_attempt_at=CURRENT_TIMESTAMP,payment_provider='WECHAT_NATIVE' WHERE id=? AND tenant_id=? AND status='PENDING' AND payment_state='CREATED'",
            order.id(), tenant) == 1);
        if (!Boolean.TRUE.equals(claimed)) return;
        String timeExpire = WechatPayV2Client.timeExpire(order.expiresAt());
        var result = client.unifiedOrder(credentials, order.outTradeNo(), order.amountCents(), description, clientIp, timeExpire);
        transactions.executeWithoutResult(status -> {
            switch (result.state()) {
                case "ACCEPTED" -> jdbc.update("UPDATE notification_resource_orders SET payment_state='ACCEPTED',code_url=?,prepay_id=?,payment_provider='WECHAT_NATIVE' WHERE id=? AND tenant_id=? AND status='PENDING' AND payment_state='IN_FLIGHT'",
                    result.codeUrl(), result.prepayId(), order.id(), tenant);
                case "REJECTED" -> jdbc.update("UPDATE notification_resource_orders SET payment_state='REJECTED',code_url=NULL,prepay_id=NULL,payment_provider='WECHAT_NATIVE_FAILED' WHERE id=? AND tenant_id=? AND status='PENDING' AND payment_state='IN_FLIGHT'",
                    order.id(), tenant);
                default -> jdbc.update("UPDATE notification_resource_orders SET payment_state='UNKNOWN',code_url=NULL,prepay_id=NULL,payment_provider='WECHAT_NATIVE_FAILED' WHERE id=? AND tenant_id=? AND status='PENDING' AND payment_state='IN_FLIGHT'",
                    order.id(), tenant);
            }
            audit.record(null, tenant, "PAYMENT_WECHAT_UNIFIED_ORDER", order.id(), result.state(), order.outTradeNo());
        });
    }

    // ------------------------------------------------------------------ callback

    /** Verify and idempotently credit an asynchronous payment notification. */
    public CallbackResult acceptCallback(Map<String, String> params) {
        if (params == null) return failure("INVALID_MESSAGE", "通知无法解析");
        if (!"SUCCESS".equals(params.get("return_code")) || !"SUCCESS".equals(params.get("result_code")))
            return failure("NOT_SUCCESSFUL", "通知不是支付成功状态");
        String outTradeNo = params.get("out_trade_no");
        String transactionId = params.get("transaction_id");
        Long amount = cents(params.get("total_fee"));
        if (outTradeNo == null || outTradeNo.isBlank()) return failure("MISSING_ORDER", "通知缺少商户订单号");
        if (transactionId == null || transactionId.isBlank()) return failure("MISSING_TRANSACTION", "通知缺少微信交易号");
        if (amount == null) return failure("INVALID_AMOUNT", "通知金额无效");
        var order = loadByOutTradeNo(outTradeNo);
        if (order == null) return failure("ORDER_UNKNOWN", "本地订单不存在");
        if (!client.verifySignature(params, config.verificationKeys(order.merchantRevision())))
            return failure("INVALID_SIGN", "通知签名未通过校验");
        if (order.merchantMchId() != null || order.merchantAppId() != null) {
            if (!Objects.equals(order.merchantMchId(), params.get("mch_id")) || !Objects.equals(order.merchantAppId(), params.get("appid")))
                return failure("MERCHANT_MISMATCH", "通知商户身份与订单快照不一致");
        } else if (!config.matchesKnownMerchant(params.get("mch_id"), params.get("appid"))) {
            return failure("MERCHANT_MISMATCH", "通知商户身份未在本地配置中登记");
        }
        if (amount != order.amountCents()) return failure("AMOUNT_MISMATCH", "通知金额与本地订单金额不一致");
        CallbackResult result = transactions.execute(status -> applyCallbackPaid(order.id(), amount, transactionId));
        return result == null ? failure("CREDIT_FAILED", "支付入账未完成") : result;
    }

    private CallbackResult applyCallbackPaid(String orderId, long amount, String transactionId) {
        var order = lock(orderId);
        if (order == null) return failure("ORDER_UNKNOWN", "本地订单不存在");
        if ("PAID".equals(order.status())) {
            if (transactionId.equals(order.transactionId()))
                return new CallbackResult(true, "ALREADY_PAID", "订单已入账，重复通知已确认");
            if (order.transactionId() == null) {
                jdbc.update("UPDATE notification_resource_orders SET transaction_id=?,paid_amount_cents=? WHERE id=? AND tenant_id=? AND transaction_id IS NULL",
                    transactionId, amount, order.id(), order.tenantId());
                audit.record(null, order.tenantId(), "PAYMENT_WECHAT_TRANSACTION_LINKED", order.id(), "LINKED", transactionId);
                return new CallbackResult(true, "LINKED_EXISTING_PAYMENT", "订单已由其他方式入账，本次仅补充微信交易号");
            }
            return failure("TRANSACTION_CONFLICT", "订单已使用其他微信交易号入账");
        }
        if (!credit(order, amount, transactionId, "WECHAT_NOTIFY"))
            return failure("CREDIT_CONFLICT", "微信交易号已被占用或资源账户不可用，未重复入账");
        return new CallbackResult(true, "PAID", "支付已确认并入账");
    }

    // ------------------------------------------------------------------ management actions

    public ActionResult query(AuthService.Identity actor, String tenant, String orderId, String request) {
        var order = transactions.execute(status -> {
            guard.begin(actor, "payments:write", tenant, "management");
            return load(orderId, tenant);
        });
        if (order == null) throw ApiError.missing();
        return compensate(actor, order, request);
    }

    public ActionResult close(AuthService.Identity actor, String tenant, String orderId, String request) {
        var order = transactions.execute(status -> {
            guard.begin(actor, "payments:write", tenant, "management");
            return load(orderId, tenant);
        });
        if (order == null) throw ApiError.missing();
        return closeOrder(actor, order, request);
    }

    public void closeCustomer(AuthService.Identity actor, String tenant, String orderId, boolean verifyOnly, String request) {
        var order = transactions.execute(status -> {
            authorizeCustomerPayment(actor, tenant);
            return loadCustomer(orderId, tenant, actor.user().id());
        });
        if (order == null) throw ApiError.missing();
        if (!"PENDING".equals(order.status())) return;
        if (verifyOnly || closeUnconfirmed(order.closeState())) compensate(actor, order, request);
        else closeOrder(actor, order, request);
    }

    static boolean closeUnconfirmed(String state) {
        return "IN_FLIGHT".equals(state) || "UNKNOWN".equals(state) || "PAID_UNCONFIRMED".equals(state);
    }

    private void authorizeCustomerPayment(AuthService.Identity actor, String tenant) {
        guard.begin(actor, "notification-recharge:write", tenant, "customer");
        guard.requireCurrent(actor, "notification-recharge:read", tenant, "customer");
    }

    private static boolean customer(AuthService.Identity actor) {
        return actor != null && "customer".equals(actor.audience());
    }

    // ------------------------------------------------------------------ worker entry points

    public void systemQuery(String orderId) {
        var order = load(orderId, null);
        if (order != null) compensate(null, order, null);
    }

    public void systemClose(String orderId) {
        var order = load(orderId, null);
        if (order != null) closeOrder(null, order, null);
    }

    private ActionResult compensate(AuthService.Identity actor, OrderRow order, String request) {
        if (!"PENDING".equals(order.status()) && !"CANCELLED".equals(order.status()) && !"PAID".equals(order.status()))
            return result(order, "query", "NOT_APPLICABLE", "该订单状态不需要查单");
        var credentials = config.credentialsForSnapshot(order.merchantRevision(), order.merchantMchId(), order.merchantAppId());
        if (credentials == null || order.outTradeNo() == null)
            return result(order, "query", "UNKNOWN", "商户凭据或订单快照不可用，未发起查单");
        Boolean claimed = transactions.execute(status -> {
            if (customer(actor)) authorizeCustomerPayment(actor, order.tenantId());
            String eligible = customer(actor)
                ? " AND created_by=? AND (query_state IS NULL OR query_state<>'IN_FLIGHT' OR last_query_at<=?)"
                : "";
            Object[] args = customer(actor)
                ? new Object[]{order.id(), order.tenantId(), actor.user().id(), time.now().minusSeconds(30)}
                : new Object[]{order.id(), order.tenantId()};
            return jdbc.update("UPDATE notification_resource_orders SET query_state='IN_FLIGHT',query_attempts=query_attempts+1,"
                + "last_query_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND status<>'FAILED'" + eligible, args) == 1;
        });
        if (!Boolean.TRUE.equals(claimed)) return result(order, "query", "UNKNOWN", "查单已被其他操作占用");
        var query = client.queryOrder(credentials, order.outTradeNo());
        var applied = transactions.execute(status -> applyQuery(order.id(), query));
        if (actor != null) audit.record(actor.user().id(), order.tenantId(), "PAYMENT_WECHAT_ORDER_QUERY", order.id(), applied.outcome(), request);
        return applied;
    }

    private ActionResult closeOrder(AuthService.Identity actor, OrderRow order, String request) {
        if ("PAID".equals(order.status()))
            throw ApiError.conflict("ORDER_ALREADY_PAID", "订单已支付，不能关闭；如需核对请使用查单");
        if ("CANCELLED".equals(order.status()))
            return result(order, "close", "ALREADY_CLOSED", "订单已是关闭状态");
        if (!"PENDING".equals(order.status())) return result(order, "close", "NOT_APPLICABLE", "该订单状态不能关闭");
        if (order.outTradeNo() == null || "CREATED".equals(order.paymentState()) || "REJECTED".equals(order.paymentState()))
            throw ApiError.conflict("ORDER_NOT_SUBMITTED", "该订单未获得微信支付受理，商户侧没有可关闭的订单");
        var credentials = config.credentialsForSnapshot(order.merchantRevision(), order.merchantMchId(), order.merchantAppId());
        if (credentials == null) return result(order, "close", "UNKNOWN", "商户凭据或订单快照不可用，未发起关单");
        OffsetDateTime earliest = order.createdAt() == null ? null : order.createdAt().plusMinutes(5);
        if (earliest != null && time.now().isBefore(earliest))
            throw ApiError.conflict("CLOSE_TOO_EARLY", "微信官方要求订单生成 5 分钟后才能关单，请稍后再试");
        Boolean claimed = transactions.execute(status -> {
            if (customer(actor)) authorizeCustomerPayment(actor, order.tenantId());
            String eligible = customer(actor)
                ? " AND created_by=? AND (close_state IS NULL OR close_state='REJECTED')"
                : " AND (close_state IS NULL OR close_state<>'IN_FLIGHT' OR last_close_attempt_at<=?)";
            Object target = customer(actor) ? actor.user().id() : time.now().minusSeconds(30);
            return jdbc.update("UPDATE notification_resource_orders SET close_state='IN_FLIGHT',close_attempts=close_attempts+1,"
                + "last_close_attempt_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND status='PENDING'"
                + " AND payment_state IN ('ACCEPTED','UNKNOWN')" + eligible, order.id(), order.tenantId(), target) == 1;
        });
        if (!Boolean.TRUE.equals(claimed)) return result(order, "close", "UNKNOWN", "关单已被其他操作占用");
        var close = client.closeOrder(credentials, order.outTradeNo());
        var applied = transactions.execute(status -> applyClose(order.id(), close));
        if ("PAID".equals(close.state())) {
            // ORDERPAID carries no amount or transaction identity: only a verified query may credit.
            var query = client.queryOrder(credentials, order.outTradeNo());
            var queried = transactions.execute(status -> applyQuery(order.id(), query));
            if (actor != null) audit.record(actor.user().id(), order.tenantId(), "PAYMENT_WECHAT_ORDER_CLOSE", order.id(), queried.outcome(), request);
            return new ActionResult("close", queried.outcome(), queried.message()
                + "；关单返回已支付，已通过查单重新核验，未直接按关单结果入账", queried.order());
        }
        if (actor != null) audit.record(actor.user().id(), order.tenantId(), "PAYMENT_WECHAT_ORDER_CLOSE", order.id(), applied.outcome(), request);
        return applied;
    }

    // ------------------------------------------------------------------ state application

    private ActionResult applyQuery(String orderId, WechatPayV2Client.Query query) {
        var order = lock(orderId);
        if (order == null) return new ActionResult("query", "UNKNOWN", "本地订单不存在", null);
        switch (query.state()) {
            case "PAID" -> {
                if (query.totalFee() == null || query.totalFee() != order.amountCents()) {
                    jdbc.update("UPDATE notification_resource_orders SET query_state='AMOUNT_MISMATCH' WHERE id=? AND tenant_id=?", order.id(), order.tenantId());
                    return result(order, "query", "UNKNOWN", "查单金额与本地订单金额不一致，未入账");
                }
                if ("PAID".equals(order.status())) {
                    jdbc.update("UPDATE notification_resource_orders SET query_state='PAID' WHERE id=? AND tenant_id=?", order.id(), order.tenantId());
                    return result(order, "query", "ALREADY_PAID", "查单确认已支付，订单此前已入账");
                }
                if (!credit(order, query.totalFee(), query.transactionId(), "WECHAT_QUERY"))
                    return result(order, "query", "UNKNOWN", "查单确认已支付但微信交易号已被占用，未重复入账");
                jdbc.update("UPDATE notification_resource_orders SET query_state='PAID' WHERE id=? AND tenant_id=?", order.id(), order.tenantId());
                return result(order, "query", "PAID", "查单确认已支付并入账");
            }
            case "CLOSED" -> {
                if ("PENDING".equals(order.status()))
                    jdbc.update("UPDATE notification_resource_orders SET status='CANCELLED',payment_state='CLOSED',close_state='CLOSED',closed_at=CURRENT_TIMESTAMP,query_state='CLOSED' WHERE id=? AND tenant_id=?",
                        order.id(), order.tenantId());
                else
                    jdbc.update("UPDATE notification_resource_orders SET query_state='CLOSED' WHERE id=? AND tenant_id=?", order.id(), order.tenantId());
                return result(order, "query", "CLOSED", "查单确认商户侧已关闭订单");
            }
            case "NOTPAY", "USERPAYING", "ACCEPT" -> {
                jdbc.update("UPDATE notification_resource_orders SET query_state=? WHERE id=? AND tenant_id=?", query.state(), order.id(), order.tenantId());
                return result(order, "query", query.state(), query.message());
            }
            case "PAYERROR", "REFUND", "REVOKED" -> {
                jdbc.update("UPDATE notification_resource_orders SET query_state=? WHERE id=? AND tenant_id=?", query.state(), order.id(), order.tenantId());
                return result(order, "query", query.state(), "查单返回 " + query.state() + "：" + query.message() + "；未变更充值或余额");
            }
            default -> {
                jdbc.update("UPDATE notification_resource_orders SET query_state='UNKNOWN' WHERE id=? AND tenant_id=?", order.id(), order.tenantId());
                return result(order, "query", "UNKNOWN", "查单结果未确认（" + query.code() + "）：" + query.message());
            }
        }
    }

    private ActionResult applyClose(String orderId, WechatPayV2Client.Close close) {
        var order = lock(orderId);
        if (order == null) return new ActionResult("close", "UNKNOWN", "本地订单不存在", null);
        return switch (close.state()) {
            case "CLOSED", "ALREADY_CLOSED" -> {
                if ("PENDING".equals(order.status()))
                    jdbc.update("UPDATE notification_resource_orders SET status='CANCELLED',payment_state='CLOSED',close_state=?,closed_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?",
                        close.state(), order.id(), order.tenantId());
                else
                    jdbc.update("UPDATE notification_resource_orders SET close_state=? WHERE id=? AND tenant_id=?", close.state(), order.id(), order.tenantId());
                yield result(order, "close", close.state(), close.message());
            }
            case "PAID" -> {
                jdbc.update("UPDATE notification_resource_orders SET close_state='PAID_UNCONFIRMED' WHERE id=? AND tenant_id=?", order.id(), order.tenantId());
                yield result(order, "close", "UNKNOWN", "关单返回商户侧已支付，尚需查单核验金额与交易号");
            }
            case "REJECTED" -> {
                jdbc.update("UPDATE notification_resource_orders SET close_state='REJECTED' WHERE id=? AND tenant_id=?", order.id(), order.tenantId());
                yield result(order, "close", "REJECTED", "关单被微信支付拒绝（" + close.code() + "）：" + close.message() + "；未变更订单状态");
            }
            default -> {
                jdbc.update("UPDATE notification_resource_orders SET close_state='UNKNOWN' WHERE id=? AND tenant_id=?", order.id(), order.tenantId());
                yield result(order, "close", "UNKNOWN", "关单结果未确认（" + close.code() + "）：" + close.message());
            }
        };
    }

    // ------------------------------------------------------------------ credit

    /**
     * Exactly-once credit. Caller holds the order row lock and has already verified signature,
     * merchant identity, merchant order number and amount.
     */
    private boolean credit(OrderRow order, long amountCents, String transactionId, String source) {
        if (amountCents != order.amountCents() || transactionId == null || transactionId.isBlank()) return false;
        var accountRows = jdbc.query("SELECT id FROM notification_resource_accounts WHERE id=? AND tenant_id=? FOR UPDATE",
            (rs, n) -> rs.getString(1), order.accountId(), order.tenantId());
        if (accountRows.isEmpty()) return false;
        var accountId = accountRows.getFirst();
        var existing = jdbc.query("SELECT order_id,amount_cents FROM payment_paid_transactions WHERE transaction_id=?",
            (rs, n) -> new Object[]{rs.getString(1), rs.getLong(2)}, transactionId);
        if (!existing.isEmpty())
            return order.id().equals(existing.getFirst()[0]) && amountCents == (Long) existing.getFirst()[1];
        jdbc.update("INSERT INTO payment_paid_transactions(transaction_id,order_id,tenant_id,amount_cents,source,credited_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
            transactionId, order.id(), order.tenantId(), amountCents, source);
        jdbc.update("UPDATE notification_resource_accounts SET " + (order.channel().equals("SMS") ? "sms_total=sms_total+?" : "voice_total=voice_total+?")
            + ",revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?", order.quantity(), accountId, order.tenantId());
        var updated = jdbc.query("SELECT sms_total,sms_used,voice_total,voice_used FROM notification_resource_accounts WHERE id=? AND tenant_id=?",
            (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, accountId, order.tenantId());
        if (updated.isEmpty()) throw new IllegalStateException("notification resource account disappeared during credit");
        var balances = updated.getFirst();
        long balance = order.channel().equals("SMS") ? balances[0] - balances[1] : balances[2] - balances[3];
        jdbc.update("INSERT INTO notification_resource_ledger(id,account_id,tenant_id,channel,kind,quantity,balance_after,source,reference,operator,created_at) VALUES (?,?,?,?,'RECHARGE',?,?,'QR',?,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(), accountId, order.tenantId(), order.channel(), order.quantity(), balance,
            order.outTradeNo(), "WECHAT_QUERY".equals(source) ? "wechat-query" : "wechat-notify");
        String closeState = "CANCELLED".equals(order.status()) ? "PAID_AFTER_CLOSE" : null;
        jdbc.update("UPDATE notification_resource_orders SET status='PAID',payment_state='PAID',transaction_id=?,paid_amount_cents=?,paid_at=CURRENT_TIMESTAMP,close_state=? WHERE id=? AND tenant_id=?",
            transactionId, amountCents, closeState, order.id(), order.tenantId());
        audit.record(null, order.tenantId(), "PAYMENT_WECHAT_PAID", order.id(), "PAID", order.outTradeNo());
        return true;
    }


    // ------------------------------------------------------------------ loading

    private OrderRow load(String orderId, String tenant) {
        var rows = jdbc.query("SELECT " + ORDER_COLUMNS + " FROM notification_resource_orders WHERE id=?" + (tenant == null ? "" : " AND tenant_id=?"),
            (rs, n) -> row(rs), tenant == null ? new Object[]{orderId} : new Object[]{orderId, tenant});
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private OrderRow loadCustomer(String orderId, String tenant, String actor) {
        var rows = jdbc.query("SELECT " + ORDER_COLUMNS + " FROM notification_resource_orders WHERE id=? AND tenant_id=? AND created_by=?",
            (rs, n) -> row(rs), orderId, tenant, actor);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private OrderRow loadByOutTradeNo(String outTradeNo) {
        var rows = jdbc.query("SELECT " + ORDER_COLUMNS + " FROM notification_resource_orders WHERE out_trade_no=?",
            (rs, n) -> row(rs), outTradeNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private OrderRow lock(String orderId) {
        var rows = jdbc.query("SELECT " + ORDER_COLUMNS + " FROM notification_resource_orders WHERE id=? FOR UPDATE",
            (rs, n) -> row(rs), orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static OrderRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("out_trade_no"), rs.getString("account_id"), rs.getString("channel"),
            rs.getLong("quantity"), rs.getLong("amount_cents"), rs.getString("status"), rs.getString("payment_state"),
            rs.getString("transaction_id"), rs.getObject("paid_amount_cents", Long.class),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("merchant_config_revision", Long.class), rs.getString("merchant_mch_id"), rs.getString("merchant_app_id"),
            rs.getString("close_state"), rs.getString("query_state"));
    }

    private ActionResult result(OrderRow order, String action, String outcome, String message) {
        return new ActionResult(action, outcome, message, state(order.id(), order.tenantId()));
    }

    private PaymentState state(String orderId, String tenant) {
        var rows = jdbc.query("SELECT status,payment_state,close_state,query_state,query_attempts,close_attempts,expires_at,paid_at FROM notification_resource_orders WHERE id=? AND tenant_id=?",
            (rs, n) -> new PaymentState(orderId, rs.getString("status"), rs.getString("payment_state"), rs.getString("close_state"),
                rs.getString("query_state"), rs.getInt("query_attempts"), rs.getInt("close_attempts"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("paid_at", OffsetDateTime.class)),
            orderId, tenant);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static CallbackResult failure(String code, String message) {
        return new CallbackResult(false, code, message);
    }

    private static Long cents(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
