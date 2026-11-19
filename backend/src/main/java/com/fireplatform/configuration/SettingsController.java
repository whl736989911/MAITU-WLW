package com.fireplatform.configuration;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/settings")
public class SettingsController {
    private final SettingsService settings; private final RequestSecurity security;
    public SettingsController(SettingsService settings,RequestSecurity security) { this.settings=settings; this.security=security; }
    @GetMapping public Map<String,List<SettingsService.SettingView>> list(HttpServletRequest request) { security.require(request,"settings:read","management"); return Map.of("items",settings.list()); }
    @PutMapping("/{key}") public SettingsService.SettingView update(@PathVariable String key,@Valid @RequestBody SettingsService.UpdateSetting body,HttpServletRequest request) {
        var identity=security.require(request,"settings:write","management"); return settings.update(identity,key,body,RequestContext.requestId(request));
    }
}
