package com.fireplatform.notification.officialaccount;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Management-only Official Account configuration. Never accepts a tenant context. */
@RestController
@RequestMapping("/api/management/wechat-official-account")
public class WechatOfficialAccountConfigController {
    private final RequestSecurity security;
    private final WechatOfficialAccountConfigService config;

    public WechatOfficialAccountConfigController(RequestSecurity security, WechatOfficialAccountConfigService config) {
        this.security = security; this.config = config;
    }

    private static void global(HttpServletRequest request) {
        if (request.getHeader("X-Tenant-Id") != null) throw ApiError.bad("公众号配置是全局配置，不接受单位上下文");
    }

    @GetMapping
    public WechatOfficialAccountConfigService.View view(HttpServletRequest request) {
        security.require(request, "notification-config:read", "management");
        global(request);
        return config.view();
    }

    @PutMapping
    public WechatOfficialAccountConfigService.View update(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                          @Valid @RequestBody WechatOfficialAccountInputs.Config body,
                                                          HttpServletRequest request) {
        var actor = security.require(request, "notification-config:write", "management");
        global(request);
        return config.update(actor, key, body, RequestContext.requestId(request));
    }
}
