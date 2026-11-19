package com.fireplatform.notification.officialaccount;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.notification.NotificationInputs;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Current-user Official Account binding for both audiences. Reuses the existing inbox self gate. */
@RestController
public class WechatOfficialAccountBindingController {
    public record Confirm(@JsonDeserialize(using = NotificationInputs.IntegerOnly.class) @NotNull Long expectedRevision,
                          @JsonDeserialize(using = NotificationInputs.TextOnly.class) @NotBlank @Size(max = 1000) String reason) { }
    public record Unbind(@JsonDeserialize(using = NotificationInputs.IntegerOnly.class) @NotNull Long expectedRevision,
                         @JsonDeserialize(using = NotificationInputs.TextOnly.class) @NotBlank @Size(max = 1000) String reason) { }

    private final RequestSecurity security;
    private final WechatOfficialAccountBindingService bindings;

    public WechatOfficialAccountBindingController(RequestSecurity security, WechatOfficialAccountBindingService bindings) {
        this.security = security; this.bindings = bindings;
    }

    private AuthService.Identity self(HttpServletRequest request) {
        return security.require(request, "inbox:read", security.identity(request).audience());
    }

    @GetMapping({"/api/customer/me/wechat-binding", "/api/management/me/wechat-binding"})
    public WechatOfficialAccountBindingService.StatusView status(HttpServletRequest request) {
        return bindings.status(self(request));
    }

    @PostMapping({"/api/customer/me/wechat-binding/requests", "/api/management/me/wechat-binding/requests"})
    public WechatOfficialAccountBindingService.StartResult start(HttpServletRequest request) {
        return bindings.start(self(request));
    }

    @PostMapping({"/api/customer/me/wechat-binding/pending/{id}/confirm", "/api/management/me/wechat-binding/pending/{id}/confirm"})
    public WechatOfficialAccountBindingService.BindingView confirm(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Confirm body, HttpServletRequest request) {
        return bindings.confirm(self(request), id, key, body.expectedRevision(), body.reason(), RequestContext.requestId(request));
    }

    @PostMapping({"/api/customer/me/wechat-binding/pending/{id}/reject", "/api/management/me/wechat-binding/pending/{id}/reject"})
    public void reject(@PathVariable String id, HttpServletRequest request) {
        bindings.cancel(self(request), id);
    }

    @PostMapping({"/api/customer/me/wechat-binding/unbind", "/api/management/me/wechat-binding/unbind"})
    public WechatOfficialAccountBindingService.UnbindResult unbind(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Unbind body, HttpServletRequest request) {
        return bindings.unbind(self(request), body.expectedRevision(), body.reason(), key, RequestContext.requestId(request));
    }

    @GetMapping({"/api/customer/me/wechat-binding/history", "/api/management/me/wechat-binding/history"})
    public Page<WechatOfficialAccountBindingService.HistoryView> history(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        self(request);
        return bindings.history(security.identity(request), page, size);
    }
}
