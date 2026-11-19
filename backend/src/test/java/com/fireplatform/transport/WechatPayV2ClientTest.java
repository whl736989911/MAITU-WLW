package com.fireplatform.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WechatPayV2ClientTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String APP_ID = "wx1234567890abcdef";
    private static final String MCH_ID = "1900000109";

    private HttpServer server;
    private WechatPayV2Client client;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responses.getOrDefault(path, "<xml><return_code><![CDATA[FAIL]]></return_code><return_msg><![CDATA[missing]]></return_msg></xml>")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new WechatPayV2Client("http://127.0.0.1:" + server.getAddress().getPort(), HttpClient.newHttpClient(), Clock.systemUTC());
    }

    @AfterEach
    void stop() { server.stop(0); }

    private static WechatPayV2Client.Credentials credentials() {
        return new WechatPayV2Client.Credentials(MCH_ID, APP_ID, KEY, "https://example.com/api/payments/wechat/native/notify");
    }

    private static String signedXml(String signerKey, Map<String, String> fields) {
        var params = new TreeMap<>(fields);
        params.put("sign", WechatPayV2Client.sign(params, signerKey));
        var xml = new StringBuilder("<xml>");
        params.forEach((name, value) -> xml.append('<').append(name).append("><![CDATA[").append(value).append("]]></").append(name).append('>'));
        return xml.append("</xml>").toString();
    }

    private static Map<String, String> paidQueryFields() {
        return Map.of("return_code", "SUCCESS", "result_code", "SUCCESS", "appid", APP_ID, "mch_id", MCH_ID,
            "out_trade_no", "NEW123456", "trade_state", "SUCCESS", "total_fee", "100", "transaction_id", "wx-tx-1");
    }

    @Test
    void unifiedOrderSignsRequestAndCarriesExplicitExpiry() throws Exception {
        responses.put("/pay/unifiedorder", signedXml(KEY, Map.of("return_code", "SUCCESS", "result_code", "SUCCESS",
            "appid", APP_ID, "mch_id", MCH_ID, "code_url", "weixin://wxpay/bizpayurl/UP", "prepay_id", "prepay-1")));
        var result = client.unifiedOrder(credentials(), "NEW123456", 100, "短信资源充值", "127.0.0.1", "20261112120000");
        assertThat(result.state()).isEqualTo("ACCEPTED");
        assertThat(result.codeUrl()).isEqualTo("weixin://wxpay/bizpayurl/UP");
        var sent = client.parseXml(requests.getFirst());
        assertThat(sent).containsEntry("time_expire", "20261112120000").containsEntry("trade_type", "NATIVE")
            .containsEntry("total_fee", "100");
        assertThat(client.verifySignature(sent, List.of(KEY))).isTrue();
    }

    @Test
    void unifiedOrderNeverReportsAcceptedForUnverifiableResponse() {
        responses.put("/pay/unifiedorder", signedXml("ffffffffffffffffffffffffffffffff", Map.of(
            "return_code", "SUCCESS", "result_code", "SUCCESS", "appid", APP_ID, "mch_id", MCH_ID,
            "code_url", "weixin://x", "prepay_id", "prepay-1")));
        assertThat(client.unifiedOrder(credentials(), "NEW123456", 100, "短信充值", "127.0.0.1", null).state())
            .isEqualTo("UNKNOWN");
        responses.put("/pay/unifiedorder", signedXml(KEY, Map.of(
            "return_code", "SUCCESS", "result_code", "FAIL", "appid", APP_ID, "mch_id", MCH_ID,
            "err_code", "SYSTEMERROR", "err_code_des", "系统繁忙")));
        assertThat(client.unifiedOrder(credentials(), "NEW123456", 100, "短信充值", "127.0.0.1", null).state())
            .isEqualTo("UNKNOWN");
    }

    @Test
    void queryRequiresSignatureMerchantOrderAndAmount() {
        responses.put("/pay/orderquery", signedXml(KEY, paidQueryFields()));
        var paid = client.queryOrder(credentials(), "NEW123456");
        assertThat(paid.state()).isEqualTo("PAID");
        assertThat(paid.totalFee()).isEqualTo(100L);
        assertThat(paid.transactionId()).isEqualTo("wx-tx-1");

        responses.put("/pay/orderquery", signedXml("ffffffffffffffffffffffffffffffff", paidQueryFields()));
        assertThat(client.queryOrder(credentials(), "NEW123456").state()).isEqualTo("UNKNOWN");

        responses.put("/pay/orderquery", signedXml(KEY, Map.of("return_code", "SUCCESS", "result_code", "SUCCESS",
            "appid", APP_ID, "mch_id", "1900000999", "out_trade_no", "NEW123456", "trade_state", "SUCCESS",
            "total_fee", "100", "transaction_id", "wx-tx-1")));
        assertThat(client.queryOrder(credentials(), "NEW123456").state()).isEqualTo("UNKNOWN");

        responses.put("/pay/orderquery", signedXml(KEY, Map.of("return_code", "SUCCESS", "result_code", "SUCCESS",
            "appid", APP_ID, "mch_id", MCH_ID, "out_trade_no", "OTHER-ORDER", "trade_state", "SUCCESS",
            "total_fee", "100", "transaction_id", "wx-tx-1")));
        assertThat(client.queryOrder(credentials(), "NEW123456").state()).isEqualTo("UNKNOWN");

        responses.put("/pay/orderquery", signedXml(KEY, Map.of("return_code", "SUCCESS", "result_code", "SUCCESS",
            "appid", APP_ID, "mch_id", MCH_ID, "out_trade_no", "NEW123456", "trade_state", "USERPAYING")));
        assertThat(client.queryOrder(credentials(), "NEW123456").state()).isEqualTo("USERPAYING");
    }

    @Test
    void closeOrderMapsOnlyVerifiedTerminalStates() {
        responses.put("/pay/closeorder", signedXml(KEY, Map.of("return_code", "SUCCESS", "result_code", "SUCCESS",
            "appid", APP_ID, "mch_id", MCH_ID)));
        assertThat(client.closeOrder(credentials(), "NEW123456").state()).isEqualTo("CLOSED");

        responses.put("/pay/closeorder", signedXml(KEY, Map.of("return_code", "SUCCESS", "result_code", "FAIL",
            "appid", APP_ID, "mch_id", MCH_ID, "err_code", "ORDERCLOSED", "err_code_des", "订单已关闭")));
        assertThat(client.closeOrder(credentials(), "NEW123456").state()).isEqualTo("ALREADY_CLOSED");

        responses.put("/pay/closeorder", signedXml(KEY, Map.of("return_code", "SUCCESS", "result_code", "FAIL",
            "appid", APP_ID, "mch_id", MCH_ID, "err_code", "ORDERPAID", "err_code_des", "订单已支付")));
        assertThat(client.closeOrder(credentials(), "NEW123456").state()).isEqualTo("PAID");

        responses.put("/pay/closeorder", signedXml(KEY, Map.of("return_code", "SUCCESS", "result_code", "FAIL",
            "appid", APP_ID, "mch_id", MCH_ID, "err_code", "SYSTEMERROR", "err_code_des", "系统繁忙")));
        assertThat(client.closeOrder(credentials(), "NEW123456").state()).isEqualTo("UNKNOWN");

        responses.put("/pay/closeorder", signedXml("ffffffffffffffffffffffffffffffff", Map.of(
            "return_code", "SUCCESS", "result_code", "SUCCESS", "appid", APP_ID, "mch_id", MCH_ID)));
        assertThat(client.closeOrder(credentials(), "NEW123456").state()).isEqualTo("UNKNOWN");
    }

    @Test
    void downloadBillDistinguishesPlainTextFromSignedError() {
        String bill = "交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,商品名称,商户数据包,手续费,费率,订单金额,申请退款金额,费率备注\n"
            + "`2026-11-11 10:00:00,wx1234567890abcdef,1900000109,0,,wx-tx-1,NEW123456,openid,NATIVE,SUCCESS,OTHERS,CNY,1.00,0.00,0,0,0.00,0.00,,,短信资源充值,,0.00600,0.60%,1.00,0.00,\n"
            + "总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额\n"
            + "`1,1.00,0.00,0.00,0.01,1.00,0.00\n";
        responses.put("/pay/downloadbill", bill);
        var ok = client.downloadBill(credentials(), "20261111");
        assertThat(ok.success()).isTrue();
        assertThat(ok.content()).contains("NEW123456");

        responses.put("/pay/downloadbill", signedXml(KEY, Map.of("return_code", "SUCCESS", "result_code", "FAIL",
            "appid", APP_ID, "mch_id", MCH_ID, "err_code", "NO_BILL_EXIST", "err_code_des", "账单不存在")));
        var missing = client.downloadBill(credentials(), "20261111");
        assertThat(missing.success()).isFalse();
        assertThat(missing.code()).isEqualTo("NO_BILL_EXIST");
    }

    @Test
    void signatureVerificationRejectsNonMd5AndAcceptsRetainedKeys() {
        var params = paidQueryFields();
        params = new TreeMap<>(params);
        params.put("sign", WechatPayV2Client.sign(params, KEY));
        assertThat(client.verifySignature(params, List.of(KEY))).isTrue();
        assertThat(client.verifySignature(params, List.of("ffffffffffffffffffffffffffffffff"))).isFalse();
        var hmac = new TreeMap<>(params);
        hmac.put("sign_type", "HMAC-SHA256");
        hmac.put("sign", WechatPayV2Client.sign(hmac, KEY));
        assertThat(client.verifySignature(hmac, List.of(KEY))).isFalse();
    }
}
