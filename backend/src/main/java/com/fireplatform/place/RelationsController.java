package com.fireplatform.place;

import com.fireplatform.device.DeviceQueryService;
import com.fireplatform.device.DeviceWorkbenchFilter;
import com.fireplatform.identity.IdentityQueries;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only all-authorized-unit overview for the relations page. */
@RestController
public class RelationsController {
    private static final int PAGE_SIZE = 100;
    private static final Set<String> KEYS = Set.of("device_page", "user_page", "size", "scope_selected", "scope_tenant_ids", "scope_place_ids");
    private final RequestSecurity security;
    private final PlaceQueries places;
    private final DeviceQueryService devices;
    private final IdentityQueries users;

    public RelationsController(RequestSecurity security, PlaceQueries places, DeviceQueryService devices, IdentityQueries users) {
        this.security = security; this.places = places; this.devices = devices; this.users = users;
    }

    public record Overview(List<PlaceQueries.PlaceNode> places, Page<DeviceQueryService.DeviceRow> devices, Page<IdentityQueries.ManagedUser> users) { }

    @GetMapping("/api/management/relations")
    public Overview get(@RequestParam(defaultValue = "1", name = "device_page") int devicePage,
                        @RequestParam(defaultValue = "1", name = "user_page") int userPage,
                        @RequestParam(defaultValue = "100") int size,
                        HttpServletRequest request) {
        var actor = security.require(request, "units:read", "management");
        if (size != PAGE_SIZE || devicePage < 1 || userPage < 1) throw ApiError.bad("关系总览分页参数无效");
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !KEYS.contains(entry.getKey()) || entry.getValue().length != 1)) throw ApiError.bad("关系总览参数未知或重复");
        var selection = security.readSelection(request);
        var placeRows = places.places(actor.user().id(), null, selection);
        Page<DeviceQueryService.DeviceRow> deviceRows = actor.permissions().contains("device:read")
            ? page(devices.workbench(actor.user().id(), null, devicePage, size, DeviceWorkbenchFilter.parse(null, null, null, null, null, null), selection))
            : new Page<>(List.of(), 0, devicePage, size);
        Page<IdentityQueries.ManagedUser> userRows = actor.permissions().contains("users:read")
            ? users.authorizedCustomers(actor.user().id(), null, selection, userPage, size, "")
            : new Page<>(List.of(), 0, userPage, size);
        return new Overview(placeRows, deviceRows, userRows);
    }

    private static Page<DeviceQueryService.DeviceRow> page(DeviceQueryService.WorkbenchResult result) {
        return new Page<>(result.items(), result.total(), result.page(), result.size());
    }
}
