package com.fireplatform.deviceservice;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Operator-only origin/account configuration for legacy TTL/TSF adapters. Credentials never read back. */
@RestController
public class LegacySourceController {
    private final RequestSecurity security;
    private final LegacySourceConfigService configuration;
    private final DriverRegistry drivers;

    public LegacySourceController(RequestSecurity security, LegacySourceConfigService configuration, DriverRegistry drivers) {
        this.security = security;
        this.configuration = configuration;
        this.drivers = drivers;
    }

    @GetMapping("/api/management/legacy-sources")
    public Map<String, List<LegacySourceConfigService.View>> list(HttpServletRequest request) {
        security.require(request, "device-services:read", "management");
        List<String> keys = drivers.all().stream().filter(IntegrationDriver::configurableOriginAccount)
            .map(IntegrationDriver::key).toList();
        return Map.of("items", configuration.views(keys, this::displayName));
    }

    @PutMapping("/api/management/legacy-sources/{key}")
    public LegacySourceConfigService.View update(@PathVariable String key,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
                                                 @Valid @RequestBody DeviceServiceInputs.UpdateLegacySource body,
                                                 HttpServletRequest request) {
        var actor = security.require(request, "integrations:write", "management");
        IntegrationDriver driver = drivers.require(key);
        if (!driver.configurableOriginAccount())
            throw ApiError.bad("该驱动不使用旧平台来源账号配置");
        return configuration.update(actor, key, driver.displayName(), body, requestKey, RequestContext.requestId(request));
    }

    private String displayName(String key) {
        IntegrationDriver driver = drivers.find(key);
        return driver == null ? key : driver.displayName();
    }
}
