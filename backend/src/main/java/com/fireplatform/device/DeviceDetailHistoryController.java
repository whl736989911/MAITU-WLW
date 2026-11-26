package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/{audience:management|customer}/devices/{id}")
public class DeviceDetailHistoryController {
    private final RequestSecurity security;
    private final DeviceDetailHistory history;

    public DeviceDetailHistoryController(RequestSecurity security, DeviceDetailHistory history) {
        this.security = security;
        this.history = history;
    }

    @GetMapping("/connectivity-history")
    public Page<DeviceDetailHistory.Connection> connectivity(@PathVariable String audience, @PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String state, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, HttpServletRequest request) {
        var actor = security.require(request, "device:read", audience);
        parameters(request, Set.of("page", "size", "state", "from", "to"));
        return history.connectivity(actor.user().id(), security.tenant(request), id, page, size, state, instant(from), instant(to));
    }

    @GetMapping(value = "/connectivity-history/export", produces = "text/csv;charset=UTF-8")
    public org.springframework.http.ResponseEntity<String> exportConnectivity(@PathVariable String audience, @PathVariable String id,
            @RequestParam(required = false) String state, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, HttpServletRequest request) {
        var actor = security.require(request, "device:read", audience);
        parameters(request, Set.of("state", "from", "to"));
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"connectivity-history.csv\"")
            .header("Cache-Control", "no-store")
            .body(history.exportConnections(actor.user().id(), security.tenant(request), id, state, instant(from), instant(to)));
    }

    @GetMapping("/lifecycle")
    public Page<DeviceDetailHistory.Lifecycle> lifecycle(@PathVariable String audience, @PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(required = false) Integer size, HttpServletRequest request) {
        var actor = security.require(request, "device:read", audience);
        parameters(request, Set.of("page", "size"));
        return history.lifecycle(actor.user().id(), security.tenant(request), id, page, size, "management".equals(audience));
    }

    private static void parameters(HttpServletRequest request, Set<String> allowed) {
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue().length != 1))
            throw ApiError.bad("设备履历查询参数无效或重复");
    }

    private static OffsetDateTime instant(String value) {
        if (value == null) return null;
        try { return OffsetDateTime.parse(value); }
        catch (java.time.format.DateTimeParseException error) { throw ApiError.bad("时间必须为带时区的ISO-8601时间"); }
    }
}
