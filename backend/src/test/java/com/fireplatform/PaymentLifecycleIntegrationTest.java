package com.fireplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.notification.NotificationResourceInputs;
import com.fireplatform.notification.NotificationResourceService;
import com.fireplatform.notification.PaymentConfigInputs;
import com.fireplatform.notification.PaymentConfigService;
import com.fireplatform.notification.PaymentOrderService;
import com.fireplatform.notification.PaymentReconciliationInputs;
import com.fireplatform.notification.PaymentReconciliationService;
import com.fireplatform.transport.WechatPayV2Client;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real lifecycle boundary tests for the WeChat Pay slice, driven through isolated local wire fixtures.
 * No real order, query, close or bill request leaves the machine; the fake transport is an HTTP server
 * on loopback and every response is signed with the merchant key the service must verify.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "app.roles=api",
    "app.payment-config-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "spring.datasource.url=jdbc:h2:mem:paymentlifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class PaymentLifecycleIntegrationTest {
    private static final String KEY1 = "a".repeat(32);
    private static final String KEY2 = "b".repeat(32);
    private static final String MCH = "1900000109";
    private static final String APP_ID = "wx1234567890abcdef";
    private static final String NOTIFY = "https://example.com/api/payments/wechat/native/notify";
    private static final String TENANT = "payment-test-unit";
    private static final String ACTOR_ID = "payment-test-user";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private static final Map<String, Map<String, String>> RESPONSES = new ConcurrentHashMap<>();
    private static final AtomicReference<String> SIGN_KEY = new AtomicReference<>(KEY1);
    private static final AtomicReference<String> BILL = new AtomicReference<>("");
    private static final HttpServer SERVER = startServer();

    private static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                exchange.getRequestBody().readAllBytes();
                String body = "/pay/downloadbill".equals(path) ? BILL.get() : signedXml(SIGN_KEY.get(), RESPONSES.getOrDefault(path, Map.of(
                    "return_code", "FAIL", "return_msg", "no fixture")));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void paymentBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("app.wechat-pay-base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @Autowired NotificationResourceService resources;
    @Autowired PaymentConfigService config;
    @Autowired PaymentOrderService payments;
    @Autowired PaymentReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ManagementGuard guard;

    private AuthService.Identity actor;

    @BeforeEach
    void setUp() {
        doNothing().when(guard).begin(any(), any(), any(), any());
        jdbc.update("DELETE FROM payment_reconciliation_differences");
        jdbc.update("DELETE FROM payment_reconciliation_runs");
        jdbc.update("DELETE FROM payment_paid_transactions");
        jdbc.update("DELETE FROM notification_resource_ledger");
        jdbc.update("DELETE FROM notification_resource_orders");
        jdbc.update("DELETE FROM notification_resource_accounts");
        jdbc.update("DELETE FROM payment_wechat_key_history");
        jdbc.update("DELETE FROM payment_wechat_config");
        jdbc.update("INSERT INTO payment_wechat_config(id) VALUES (1)");
        jdbc.update("DELETE FROM idempotency_records WHERE user_id=?", ACTOR_ID);
        jdbc.update("DELETE FROM tenants WHERE id=?", TENANT);
        jdbc.update("INSERT INTO tenants(id,name) VALUES (?,?)", TENANT, "支付测试单位");
        jdbc.update("DELETE FROM app_users WHERE id=?", ACTOR_ID);
        jdbc.update("INSERT INTO app_users(id,username,display_name,user_type,password_hash,must_change_password,enabled) VALUES (?,?,?,'PLATFORM',?,FALSE,TRUE)",
            ACTOR_ID, ACTOR_ID, "支付测试", "unused-payment-test");
        actor = new AuthService.Identity(new AuthService.UserView(ACTOR_ID, ACTOR_ID, "支付测试", "PLATFORM", null, false),
            List.of("notification-resources:write", "payments:write", "payments:read"), "management", "fixture-token");
        SIGN_KEY.set(KEY1);
        RESPONSES.clear();
        RESPONSES.put("/pay/unifiedorder", Map.of("return_code", "SUCCESS", "result_code", "SUCCESS",
            "appid", APP_ID, "mch_id", MCH, "code_url", "weixin://wxpay/bizpayurl/PAY", "prepay_id", "prepay-1"));
        BILL.set("");
    }

    private void configure(String key, long expectedRevision) {
        config.update(actor, "payment-config-" + expectedRevision,
            new PaymentConfigInputs.Config(true, MCH, APP_ID, key, NOTIFY, null, false, false, expectedRevision), "request-config");
    }

    private NotificationResourceService.Order newOrder(String idemKey) {
        return resources.createOrder(actor, TENANT, idemKey,
            new NotificationResourceInputs.OrderCreate("ORGANIZATION", TENANT, "SMS", 10L), "127.0.0.1", "request-order");
    }

    private static Map<String, String> callback(String outTradeNo, long amount, String transactionId, String signerKey,
                                                String mchId, String appId) {
        var params = new TreeMap<String, String>();
        params.put("return_code", "SUCCESS");
        params.put("result_code", "SUCCESS");
        params.put("appid", appId);
        params.put("mch_id", mchId);
        params.put("out_trade_no", outTradeNo);
        params.put("total_fee", String.valueOf(amount));
        params.put("transaction_id", transactionId);
        params.put("sign", WechatPayV2Client.sign(params, signerKey));
        return params;
    }

    private long smsTotal(String accountId) {
        return jdbc.queryForObject("SELECT sms_total FROM notification_resource_accounts WHERE id=?", Long.class, accountId);
    }

    @Test
    void rotatedMerchantKeyStillVerifiesLateCallbackExactlyOnce() {
        configure(KEY1, 0L);
        var order = newOrder("order-key-1");
        assertThat(order.codeUrl()).isEqualTo("weixin://wxpay/bizpayurl/PAY");
        assertThat(order.paymentState()).isEqualTo("ACCEPTED");
        assertThat(order.expiresAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT merchant_config_revision FROM notification_resource_orders WHERE id=?", Long.class, order.id()))
            .isEqualTo(1L);

        configure(KEY2, 1L);
        assertThat(config.verificationKeys(1L)).contains(KEY1, KEY2);

        var accepted = payments.acceptCallback(callback(order.outTradeNo(), order.amountCents(), "wx-tx-1", KEY1, MCH, APP_ID));
        assertThat(accepted.acknowledged()).isTrue();
        assertThat(accepted.code()).isEqualTo("PAID");
        assertThat(smsTotal(order.accountId())).isEqualTo(10L);

        var duplicate = payments.acceptCallback(callback(order.outTradeNo(), order.amountCents(), "wx-tx-1", KEY1, MCH, APP_ID));
        assertThat(duplicate.acknowledged()).isTrue();
        assertThat(duplicate.code()).isEqualTo("ALREADY_PAID");
        assertThat(smsTotal(order.accountId())).isEqualTo(10L);

        assertThat(payments.acceptCallback(callback(order.outTradeNo(), order.amountCents() + 1, "wx-tx-1", KEY1, MCH, APP_ID)).acknowledged()).isFalse();
        assertThat(payments.acceptCallback(callback(order.outTradeNo(), order.amountCents(), "wx-tx-1", KEY2, "1900000999", APP_ID)).acknowledged()).isFalse();
        assertThat(payments.acceptCallback(callback(order.outTradeNo(), order.amountCents(), "wx-tx-1", "c".repeat(32), MCH, APP_ID)).acknowledged()).isFalse();
        assertThat(smsTotal(order.accountId())).isEqualTo(10L);
    }

    @Test
    void uncertainCloseKeepsPendingAndVerifiedQueryCreditsLatePayment() {
        configure(KEY1, 0L);
        var order = newOrder("order-key-2");
        jdbc.update("UPDATE notification_resource_orders SET created_at=? WHERE id=?", OffsetDateTime.now(BEIJING).minusMinutes(10), order.id());

        RESPONSES.put("/pay/closeorder", Map.of("return_code", "SUCCESS", "result_code", "FAIL",
            "appid", APP_ID, "mch_id", MCH, "err_code", "SYSTEMERROR", "err_code_des", "系统繁忙"));
        var uncertain = payments.close(actor, TENANT, order.id(), "request-close");
        assertThat(uncertain.outcome()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_resource_orders WHERE id=?", String.class, order.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT close_state FROM notification_resource_orders WHERE id=?", String.class, order.id())).isEqualTo("UNKNOWN");

        RESPONSES.put("/pay/closeorder", Map.of("return_code", "SUCCESS", "result_code", "FAIL",
            "appid", APP_ID, "mch_id", MCH, "err_code", "ORDERCLOSED", "err_code_des", "订单已关闭"));
        var closed = payments.close(actor, TENANT, order.id(), "request-close");
        assertThat(closed.outcome()).isEqualTo("ALREADY_CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_resource_orders WHERE id=?", String.class, order.id())).isEqualTo("CANCELLED");

        RESPONSES.put("/pay/orderquery", Map.of("return_code", "SUCCESS", "result_code", "SUCCESS",
            "appid", APP_ID, "mch_id", MCH, "out_trade_no", order.outTradeNo(), "trade_state", "SUCCESS",
            "total_fee", String.valueOf(order.amountCents()), "transaction_id", "wx-late-1"));
        var late = payments.query(actor, TENANT, order.id(), "request-query");
        assertThat(late.outcome()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_resource_orders WHERE id=?", String.class, order.id())).isEqualTo("PAID");
        assertThat(smsTotal(order.accountId())).isEqualTo(10L);

        var again = payments.query(actor, TENANT, order.id(), "request-query");
        assertThat(again.outcome()).isEqualTo("ALREADY_PAID");
        assertThat(smsTotal(order.accountId())).isEqualTo(10L);
    }

    @Test
    void customerChecksUnknownLegacyOrderWithoutSubmittingAnotherClose() {
        configure(KEY1, 0L);
        var order = newOrder("customer-close-old-order");
        jdbc.update("UPDATE notification_resource_orders SET created_at=? WHERE id=?",
            OffsetDateTime.now(BEIJING).minusDays(3), order.id());
        var customer = new AuthService.Identity(new AuthService.UserView(ACTOR_ID, ACTOR_ID, "fixture", "CUSTOMER", TENANT, false),
            List.of("notification-recharge:read", "notification-recharge:write"), "customer", "fixture");
        RESPONSES.put("/pay/closeorder", Map.of("return_code", "SUCCESS", "result_code", "FAIL",
            "appid", APP_ID, "mch_id", MCH, "err_code", "SYSTEMERROR"));
        payments.closeCustomer(customer, TENANT, order.id(), false, "first-close");
        var pending = resources.customerOrder(TENANT, order.id(), ACTOR_ID);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.closeState()).isEqualTo("UNKNOWN");
        assertThat(pending.codeUrl()).isNull();

        RESPONSES.put("/pay/orderquery", Map.of("return_code", "SUCCESS", "result_code", "SUCCESS",
            "appid", APP_ID, "mch_id", MCH, "out_trade_no", order.outTradeNo(), "trade_state", "CLOSED"));
        payments.closeCustomer(customer, TENANT, order.id(), false, "check-close");
        assertThat(resources.customerOrder(TENANT, order.id(), ACTOR_ID).status()).isEqualTo("CANCELLED");
        assertThat(resources.order(TENANT, order.id()).closeAttempts()).isEqualTo(1);
        assertThat(smsTotal(order.accountId())).isZero();
    }

    @Test
    void reconciliationRecordsDifferencesWithoutCrediting() {
        configure(KEY1, 0L);
        var order = newOrder("order-key-3");
        String billDate = LocalDate.now(BEIJING).minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        jdbc.update("UPDATE notification_resource_orders SET created_at=? WHERE id=?",
            LocalDate.now(BEIJING).minusDays(1).atTime(12, 0).atZone(BEIJING).toOffsetDateTime(), order.id());

        BILL.set(billHeader()
            + billRow("2026-11-11 12:00:00", order.outTradeNo(), "wx-bill-1", "1.00") + "\n"
            + billRow("2026-11-11 12:30:00", "EXTERNAL-ORDER-9", "wx-bill-external", "2.00") + "\n"
            + "总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额\n"
            + "`2,3.00,0.00,0.00,0.02,3.00,0.00\n"
            + "malformed,row\n");

        var run = reconciliation.run(actor, "bill-key-1", new PaymentReconciliationInputs.Run(billDate), "request-bill");
        assertThat(run.run().status()).isEqualTo("COMPLETED");
        assertThat(run.run().externalRows()).isEqualTo(1L);
        assertThat(run.run().malformedRows()).isEqualTo(1L);
        assertThat(run.differences()).extracting(PaymentReconciliationService.Difference::kind)
            .contains("BILL_PAID_LOCAL_UNPAID", "MALFORMED_ROW");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_resource_orders WHERE id=?", String.class, order.id())).isEqualTo("PENDING");
        assertThat(smsTotal(order.accountId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_paid_transactions", Long.class)).isZero();

        var replay = reconciliation.run(actor, "bill-key-1", new PaymentReconciliationInputs.Run(billDate), "request-bill");
        assertThat(replay.run().id()).isEqualTo(run.run().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reconciliation_runs", Long.class)).isEqualTo(1L);
    }

    private static String billHeader() {
        return "交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,商品名称,商户数据包,手续费,费率,订单金额,申请退款金额,费率备注\n";
    }

    private static String billRow(String time, String outTradeNo, String transactionId, String amountYuan) {
        String[] fields = new String[27];
        fields[0] = time; fields[1] = APP_ID; fields[2] = MCH; fields[3] = "0"; fields[4] = "";
        fields[5] = transactionId; fields[6] = outTradeNo; fields[7] = "openid"; fields[8] = "NATIVE"; fields[9] = "SUCCESS";
        fields[10] = "OTHERS"; fields[11] = "CNY"; fields[12] = amountYuan; fields[13] = "0.00";
        fields[14] = "0"; fields[15] = "0"; fields[16] = "0.00"; fields[17] = "0.00"; fields[18] = ""; fields[19] = "";
        fields[20] = "短信资源充值"; fields[21] = ""; fields[22] = "0.00600"; fields[23] = "0.60%";
        fields[24] = amountYuan; fields[25] = "0.00"; fields[26] = "";
        return "`" + String.join(",", Arrays.asList(fields));
    }

    private static String signedXml(String signerKey, Map<String, String> fields) {
        var params = new TreeMap<>(fields);
        params.put("sign", WechatPayV2Client.sign(params, signerKey));
        var xml = new StringBuilder("<xml>");
        params.forEach((name, value) -> xml.append('<').append(name).append("><![CDATA[").append(value).append("]]></").append(name).append('>'));
        return xml.append("</xml>").toString();
    }
}
