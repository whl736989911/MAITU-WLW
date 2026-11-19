package com.fireplatform.notification.officialaccount;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Management selector support for unit contacts/rules; never returns an OpenID or a secret. */
@RestController
@RequestMapping("/api/management/notification-wechat")
public class WechatOfficialAccountRecipientsController {
    private final RequestSecurity security;
    private final WechatOfficialAccountBindingService bindings;
    private final WechatOfficialAccountConfigService config;

    public WechatOfficialAccountRecipientsController(RequestSecurity security, WechatOfficialAccountBindingService bindings,
                                                     WechatOfficialAccountConfigService config) {
        this.security = security; this.bindings = bindings; this.config = config;
    }

    @GetMapping("/recipients")
    public List<WechatOfficialAccountBindingService.RecipientView> recipients(HttpServletRequest request) {
        security.require(request, "notification-config:read", "management");
        return bindings.recipients(security.tenant(request));
    }

    @GetMapping("/channel")
    public Map<String, Object> channel(HttpServletRequest request) {
        security.require(request, "notification-config:read", "management");
        var tenant = security.tenant(request);
        var view = config.view();
        var recipients = bindings.recipients(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", view.configured());
        out.put("enabled", view.enabled());
        out.put("ready", view.enabled() && view.oauthReady() && (view.fireTemplateReady() || view.faultTemplateReady()));
        out.put("fire_template_ready", view.fireTemplateReady());
        out.put("fault_template_ready", view.faultTemplateReady());
        out.put("active_recipients", recipients.size());
        out.put("message", !view.configured() ? "平台尚未配置公众号"
            : !view.enabled() ? "公众号通道未启用"
            : !(view.fireTemplateReady() || view.faultTemplateReady()) ? "公众号模板未配置完整"
            : "公众号通道可用；发送仍受单位通道策略和通知规则约束");
        return out;
    }
}
