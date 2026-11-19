package com.fireplatform.notification;

import com.fireplatform.transport.WechatPayV2Client;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * WeChat Pay APIv2 Native payment notification.
 *
 * <p>The endpoint is public, so the payload alone is untrusted: signature, retained merchant identity,
 * merchant order number, amount and transaction identity are verified against the order snapshot before
 * any credit. A failure response keeps the provider's retry window open; every unverifiable payload is
 * rejected without changing a balance.</p>
 */
@RestController
public class WechatPayCallbackController {
    private static final Logger log = LoggerFactory.getLogger(WechatPayCallbackController.class);
    private static final int MAX_CALLBACK_BYTES = 64 * 1024;
    private final WechatPayV2Client wechat;
    private final PaymentOrderService payments;

    public WechatPayCallbackController(WechatPayV2Client wechat, PaymentOrderService payments) {
        this.wechat = wechat; this.payments = payments;
    }

    @PostMapping(value="/api/payments/wechat/native/notify", produces="application/xml")
    public void notify(@RequestBody String body, HttpServletResponse response) throws IOException {
        response.setContentType("application/xml;charset=UTF-8");
        if (body == null || body.isBlank() || body.length() > MAX_CALLBACK_BYTES) {
            write(response, "FAIL", "invalid payload");
            return;
        }
        Map<String, String> params;
        try {
            params = wechat.parseXml(body);
        } catch (Exception invalid) {
            write(response, "FAIL", "invalid xml");
            return;
        }
        try {
            var result = payments.acceptCallback(params);
            if (result.acknowledged()) {
                write(response, "SUCCESS", "OK");
                log.info("wechat pay callback {} order={}", result.code(), params.get("out_trade_no"));
            } else {
                write(response, "FAIL", "rejected");
                log.warn("wechat pay callback rejected code={} order={}", result.code(), params.get("out_trade_no"));
            }
        } catch (RuntimeException failure) {
            write(response, "FAIL", "temporary failure");
            log.warn("wechat pay callback failed order={} type={}", params.get("out_trade_no"), failure.getClass().getSimpleName());
        }
    }

    private static void write(HttpServletResponse response, String code, String message) throws IOException {
        String xml = "<xml><return_code><![CDATA[" + code + "]]></return_code><return_msg><![CDATA[" + message + "]]></return_msg></xml>";
        response.getOutputStream().write(xml.getBytes(StandardCharsets.UTF_8));
    }
}
