package com.fireplatform.notification.officialaccount;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public Official Account endpoints. These are the only unauthenticated WeChat routes and are
 * matched exactly in {@code RequestSecurity}; there is no generic {@code /api/wechat/*} bypass.
 *
 * <p>Reverse proxies must not log the query string or body for these paths. The controller never
 * redirects to a caller-supplied URL and never returns a token or OpenID.</p>
 */
@RestController
public class WechatOfficialAccountPublicController {
    private static final Logger log = LoggerFactory.getLogger(WechatOfficialAccountPublicController.class);
    private static final int MAX_CALLBACK_BYTES = 64 * 1024;

    private final WechatOfficialAccountCallbackService callbacks;
    private final WechatOfficialAccountBindingService bindings;

    public WechatOfficialAccountPublicController(WechatOfficialAccountCallbackService callbacks, WechatOfficialAccountBindingService bindings) {
        this.callbacks = callbacks; this.bindings = bindings;
    }

    @GetMapping(value = "/api/wechat/callback", produces = "text/plain;charset=UTF-8")
    public void verify(@RequestParam(required = false) String signature, @RequestParam(required = false) String timestamp,
                       @RequestParam(required = false) String nonce, @RequestParam(required = false) String echostr,
                       @RequestParam(name = "msg_signature", required = false) String msgSignature,
                       @RequestParam(name = "encrypt_type", required = false) String encryptType,
                       HttpServletResponse response) throws IOException {
        String plain = callbacks.verify(signature, timestamp, nonce, echostr, msgSignature, encryptType);
        response.setHeader("Cache-Control", "no-store");
        if (plain == null) { response.setStatus(403); return; }
        response.setContentType("text/plain;charset=UTF-8");
        response.getOutputStream().write(plain.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/api/wechat/callback", produces = "text/plain;charset=UTF-8")
    public void receive(@RequestBody(required = false) byte[] body,
                        @RequestParam(required = false) String signature, @RequestParam(required = false) String timestamp,
                        @RequestParam(required = false) String nonce,
                        @RequestParam(name = "msg_signature", required = false) String msgSignature,
                        @RequestParam(name = "encrypt_type", required = false) String encryptType,
                        HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (body == null || body.length == 0 || body.length > MAX_CALLBACK_BYTES) { response.setStatus(403); return; }
        try {
            var result = callbacks.receive(body, signature, timestamp, nonce, encryptType, msgSignature);
            if (!result.accepted()) {
                response.setStatus(403);
                return;
            }
            response.setContentType("text/plain;charset=UTF-8");
            response.getOutputStream().write("success".getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            // Keep the provider retry window open without writing a raw body or OpenID to the log.
            log.warn("wechat callback failed: {}", failure.getClass().getSimpleName());
            response.setStatus(503);
        }
    }

    @GetMapping(value = "/api/wechat/oauth/callback", produces = "text/html;charset=UTF-8")
    public void oauthCallback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                              HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        var outcome = bindings.callback(state, code);
        String title = outcome.ok() ? "微信授权已完成" : "微信授权未完成";
        String detail = outcome.ok()
            ? "请返回发起授权的平台页面，在本人当前登录会话中确认绑定。未确认前不会自动绑定。"
            : "原因：" + escape(outcome.message()) + "。请返回平台页面重新发起。";
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\">"
            + "<title>" + title + "</title><style>body{font-family:system-ui,sans-serif;margin:3rem auto;max-width:36rem;padding:0 1.2rem;color:#1f2937}"
            + "h1{font-size:1.35rem}p{line-height:1.7}a{display:inline-block;margin-top:1.2rem;padding:.7rem 1.2rem;border:1px solid #9ca3af;border-radius:.5rem;background:#fff;color:inherit;text-decoration:none}"
            + "</style></head><body><h1>" + title + "</h1><p>" + detail + "</p>"
            + "<a href=\"/\">重新打开平台</a>"
            + "<p>此入口需重新登录；已登录的平台页请直接切回原标签页继续操作。</p>"
            + "<p style=\"color:#6b7280;font-size:.85rem\">本页面不显示微信凭据、OpenID 或平台登录令牌。</p></body></html>";
        response.setStatus(200);
        response.setContentType("text/html;charset=UTF-8");
        response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
