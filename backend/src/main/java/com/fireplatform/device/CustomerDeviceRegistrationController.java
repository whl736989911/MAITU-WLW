package com.fireplatform.device;

import com.fireplatform.deviceservice.DeviceRegistrationService;
import com.fireplatform.deviceservice.DeviceServiceInputs;
import com.fireplatform.deviceservice.DeviceServiceModels;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Customer entry to the existing vendor registration/task truth. The tenant is fixed by the session;
 * the unassigned pool, global registration keys and product-model administration are not reachable.
 * Cloud acceptance never masquerades as local commissioning. The service returns minimized DTOs with
 * no vendor credentials, account fingerprint, raw cloud result or event JSON.
 */
@RestController
@RequestMapping("/api/customer/device-registrations")
public class CustomerDeviceRegistrationController {
    private final RequestSecurity security;
    private final DeviceRegistrationService registrations;

    public CustomerDeviceRegistrationController(RequestSecurity security, DeviceRegistrationService registrations) {
        this.security = security; this.registrations = registrations;
    }

    @GetMapping("/drivers")
    public Map<String, List<DeviceRegistrationService.DriverOption>> driverOptions(HttpServletRequest request) {
        security.require(request, "device:write", "customer");
        return Map.of("items", registrations.driverOptionsForCustomer().stream()
            .filter(driver -> "VENDOR_PLATFORM".equals(driver.mode())).toList());
    }

    @GetMapping
    public Page<DeviceServiceModels.CustomerRegistrationSummary> list(@RequestParam(defaultValue = "1") int page,
                                                                       @RequestParam(required = false) Integer size,
                                                                       HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !java.util.Set.of("page", "size").contains(entry.getKey()) || entry.getValue().length != 1))
            throw ApiError.bad("登记分页参数无效或重复");
        return registrations.pageForCustomer(actor, page, size);
    }

    @PostMapping
    public ResponseEntity<DeviceServiceModels.CustomerRegistrationDetail> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                                 @Valid @RequestBody DeviceServiceInputs.CreateRegistration body,
                                                                                 HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        return ResponseEntity.status(201).body(registrations.createForCustomer(actor, key, body, RequestContext.requestId(request)));
    }

    @GetMapping("/{id}")
    public DeviceServiceModels.CustomerRegistrationDetail detail(@PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        return registrations.detailForCustomer(actor, id);
    }

    @PostMapping("/{id}/resume")
    public DeviceServiceModels.CustomerRegistrationDetail resume(@PathVariable String id,
                                                                 HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        return registrations.resumeForCustomer(actor, id, RequestContext.requestId(request));
    }

    @PostMapping("/{id}/reconcile")
    public DeviceServiceModels.CustomerRegistrationDetail reconcile(@PathVariable String id,
                                                                    HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        return registrations.reconcileForCustomer(actor, id, RequestContext.requestId(request));
    }

    @PostMapping("/{id}/commission")
    public DeviceServiceModels.CustomerRegistrationDetail commission(@PathVariable String id,
                                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                     @Valid @RequestBody DeviceServiceInputs.CommissionRegistration body,
                                                                     HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        return registrations.commissionForCustomer(actor, id, key, body, RequestContext.requestId(request));
    }

    @PostMapping("/{id}/abandon")
    public DeviceServiceModels.CustomerRegistrationDetail abandon(@PathVariable String id,
                                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                  @Valid @RequestBody DeviceServiceInputs.AbandonRegistration body,
                                                                  HttpServletRequest request) {
        var actor = security.require(request, "device:write", "customer");
        return registrations.abandonForCustomer(actor, id, key, body, RequestContext.requestId(request));
    }
}
