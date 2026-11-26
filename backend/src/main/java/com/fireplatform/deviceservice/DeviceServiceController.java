package com.fireplatform.deviceservice;

import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Common lifecycle/service endpoints for both PRODUCT_MODEL and VENDOR_PLATFORM drivers. */
@RestController
public class DeviceServiceController {
    private final RequestSecurity security;
    private final DeviceServiceQueryService queries;
    private final DeviceServiceCommandService commands;
    private final DeviceLifecycleService lifecycle;
    private final DeviceRegistrationService registrations;
    private final DeviceServiceDefinitionService definitions;
    private final IntegrationDriverConfigService driverConfig;
    private final DriverRegistry drivers;
    private final DeviceCapabilityEvidenceService evidence;

    public DeviceServiceController(RequestSecurity security, DeviceServiceQueryService queries,
                                   DeviceServiceCommandService commands, DeviceLifecycleService lifecycle,
                                   DeviceRegistrationService registrations,
                                   DeviceServiceDefinitionService definitions,
                                   IntegrationDriverConfigService driverConfig, DriverRegistry drivers,
                                   DeviceCapabilityEvidenceService evidence) {
        this.security = security;
        this.queries = queries;
        this.commands = commands;
        this.lifecycle = lifecycle;
        this.registrations = registrations;
        this.definitions = definitions;
        this.driverConfig = driverConfig;
        this.drivers = drivers;
        this.evidence = evidence;
    }

    // ---------------- device service reads ----------------

    @GetMapping("/api/{audience:management|customer}/devices/{id}/service-capabilities")
    public DeviceServiceModels.Capabilities capabilities(@PathVariable String audience, @PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "device-services:read", audience);
        return queries.capabilities(actor.user().id(), security.tenant(request), id);
    }

    @GetMapping("/api/{audience:management|customer}/devices/{id}/service-parameters")
    public DeviceServiceQueryService.ParameterRead parameters(@PathVariable String audience, @PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "device-services:read", audience);
        return queries.parameters(actor.user().id(), security.tenant(request), id);
    }

    @GetMapping("/api/{audience:management|customer}/devices/{id}/source-bindings")
    public Map<String, List<DeviceServiceModels.SourceBinding>> sourceBindings(@PathVariable String audience, @PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "device-services:read", audience);
        return Map.of("items", queries.sourceBindings(actor.user().id(), security.tenant(request), id));
    }

    @GetMapping("/api/{audience:management|customer}/devices/{id}/service-commands")
    public Page<DeviceServiceModels.CommandSummary> commands(@PathVariable String audience, @PathVariable String id, @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(required = false) Integer size, @RequestParam(required = false) String serviceKey, HttpServletRequest request) {
        var actor = security.require(request, "device-services:read", audience);
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !List.of("page", "size", "serviceKey").contains(entry.getKey()) || entry.getValue().length != 1))
            throw ApiError.bad("命令查询参数无效或重复");
        return commands.page(actor.user().id(), security.tenant(request), id, page, size, serviceKey);
    }

    @GetMapping("/api/{audience:management|customer}/device-service-commands/{id}")
    public DeviceServiceModels.CommandDetail command(@PathVariable String audience, @PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "device-services:read", audience);
        return commands.detail(actor.user().id(), security.tenant(request), id);
    }

    @PostMapping("/api/{audience:management|customer}/devices/{id}/service-commands")
    public DeviceServiceModels.CommandDetail createCommand(@PathVariable String audience, @PathVariable String id,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                           @Valid @RequestBody DeviceServiceInputs.CreateCommand body,
                                                           HttpServletRequest request) {
        var actor = either(request, audience, "device-services:execute", "device-services:engineering");
        return commands.create(actor, audience, security.tenant(request), id, key, body, RequestContext.requestId(request));
    }

    @PostMapping("/api/{audience:management|customer}/device-service-commands/{id}/cancel")
    public DeviceServiceModels.CommandDetail cancel(@PathVariable String audience, @PathVariable String id,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                    @Valid @RequestBody DeviceServiceInputs.CancelCommand body,
                                                    HttpServletRequest request) {
        var actor = either(request, audience, "device-services:execute", "device-services:engineering");
        return commands.cancel(actor, audience, security.tenant(request), id, body, RequestContext.requestId(request));
    }

    @PostMapping("/api/{audience:management|customer}/device-service-commands/{id}/reconcile")
    public DeviceServiceModels.CommandDetail reconcile(@PathVariable String audience, @PathVariable String id,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       @Valid @RequestBody DeviceServiceInputs.ReconcileCommand body,
                                                       HttpServletRequest request) {
        var actor = either(request, audience, "device-services:query", "device-services:engineering");
        return commands.reconcile(actor, audience, security.tenant(request), id, body, RequestContext.requestId(request));
    }

    // ---------------- drivers and capability evidence ----------------

    @GetMapping("/api/management/integration-drivers")
    public Map<String, List<DeviceServiceModels.Driver>> drivers(HttpServletRequest request) {
        security.require(request, "device-services:read", "management");
        return Map.of("items", queries.drivers());
    }

    @PutMapping("/api/management/integration-drivers/{key}/configuration")
    public IntegrationDriverConfigService.View configure(@PathVariable String key,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
                                                         @Valid @RequestBody DeviceServiceInputs.UpdateDriverConfig body,
                                                         HttpServletRequest request) {
        var actor = security.require(request, "integrations:write", "management");
        IntegrationDriver driver = drivers.require(key);
        if (!"PRODUCT_MODEL".equals(driver.mode()))
            throw ApiError.bad("该驱动凭据由厂商接入配置维护，不能在此修改");
        return driverConfig.update(actor, key, body, requestKey, RequestContext.requestId(request));
    }

    @PostMapping("/api/management/integration-drivers/{key}/capability-evidence")
    public DeviceCapabilityEvidenceService.EvidenceView evidence(@PathVariable String key,
                                                                 @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
                                                                 @Valid @RequestBody DeviceServiceInputs.CapabilityEvidence body,
                                                                 HttpServletRequest request) {
        var actor = security.require(request, "device-services:engineering", "management");
        IntegrationDriver driver = drivers.require(key);
        if (!"VENDOR_PLATFORM".equals(driver.mode()))
            throw ApiError.bad("能力证据仅用于厂商平台驱动；产品模型能力由已发布服务定义声明");
        if (driver.services().stream().noneMatch(service -> service.key().equals(body.serviceKey())))
            throw ApiError.bad("该驱动不存在指定服务操作");
        return evidence.upsert(actor, key, body, requestKey, RequestContext.requestId(request));
    }

    // ---------------- product version service definitions ----------------

    @GetMapping("/api/management/product-versions/{id}/service-definitions")
    public Map<String, List<DeviceServiceModels.ServiceDefinition>> versionDefinitions(@PathVariable String id, HttpServletRequest request) {
        security.require(request, "products:read", "management");
        return Map.of("items", queries.productDefinitions(id));
    }

    @PostMapping("/api/management/product-versions/{id}/service-definitions")
    public DeviceServiceModels.ServiceDefinition createDefinition(@PathVariable String id,
                                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                  @Valid @RequestBody DeviceServiceInputs.CreateServiceDefinition body,
                                                                  HttpServletRequest request) {
        var actor = security.require(request, "products:write", "management");
        return queries.productDefinition(definitions.create(actor, id, key, body, RequestContext.requestId(request)));
    }

    @PutMapping("/api/management/product-service-definitions/{id}")
    public DeviceServiceModels.ServiceDefinition updateDefinition(@PathVariable String id,
                                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                  @Valid @RequestBody DeviceServiceInputs.UpdateServiceDefinition body,
                                                                  HttpServletRequest request) {
        var actor = security.require(request, "products:write", "management");
        return queries.productDefinition(definitions.update(actor, id, key, body, RequestContext.requestId(request)));
    }

    @DeleteMapping("/api/management/product-service-definitions/{id}")
    public DeviceServiceModels.IdResult deleteDefinition(@PathVariable String id,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                         @Valid @RequestBody DeviceServiceInputs.DeleteServiceDefinition body,
                                                         HttpServletRequest request) {
        var actor = security.require(request, "products:write", "management");
        definitions.delete(actor, id, key, body, RequestContext.requestId(request));
        return new DeviceServiceModels.IdResult(id);
    }

    // ---------------- explicit unbind / cloud delete ----------------

    @PostMapping("/api/management/devices/{id}/unbind")
    public DeviceServiceModels.SourceBinding unbind(@PathVariable String id,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                    @Valid @RequestBody DeviceServiceInputs.UnbindSource body,
                                                    HttpServletRequest request) {
        var actor = security.require(request, "device:write", "management");
        return lifecycle.unbind(actor, security.tenant(request), id, key, body, RequestContext.requestId(request));
    }

    @PostMapping("/api/management/devices/{id}/cloud-delete")
    public DeviceServiceModels.CommandDetail cloudDelete(@PathVariable String id,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                         @Valid @RequestBody DeviceServiceInputs.CloudDelete body,
                                                         HttpServletRequest request) {
        var actor = security.require(request, "device:write", "management");
        security.require(request, "device-services:engineering", "management");
        return commands.createCloudDelete(actor, security.tenant(request), id, key, body, RequestContext.requestId(request));
    }

    // ---------------- registrations ----------------

    @GetMapping("/api/management/device-registrations")
    public Page<DeviceServiceModels.RegistrationSummary> registrations(@RequestParam(defaultValue = "1") int page,
                                                                       @RequestParam(required = false) Integer size,
                                                                       HttpServletRequest request) {
        var actor = security.require(request, "device:write", "management");
        return registrations.page(actor, security.optionalTenant(request), page, size);
    }

    @PostMapping("/api/management/device-registrations")
    public DeviceServiceModels.RegistrationDetail createRegistration(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody DeviceServiceInputs.CreateRegistration body, HttpServletRequest request) {
        var actor = security.require(request, "device:write", "management");
        return registrations.create(actor, security.optionalTenant(request), key, body, RequestContext.requestId(request));
    }

    @GetMapping("/api/management/device-registrations/{id}")
    public DeviceServiceModels.RegistrationDetail registration(@PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "device:write", "management");
        return registrations.detail(actor, id, false);
    }

    @PostMapping("/api/management/device-registrations/{id}/resume")
    public DeviceServiceModels.RegistrationDetail resume(@PathVariable String id,
                                                         HttpServletRequest request) {
        var actor = security.require(request, "device:write", "management");
        return registrations.resume(actor, id, RequestContext.requestId(request));
    }

    @PostMapping("/api/management/device-registrations/{id}/reconcile")
    public DeviceServiceModels.RegistrationDetail reconcileRegistration(
            @PathVariable String id, HttpServletRequest request) {
        var actor = security.require(request, "device:write", "management");
        return registrations.reconcile(actor, id, RequestContext.requestId(request));
    }

    @PostMapping("/api/management/device-registrations/{id}/commission")
    public DeviceServiceModels.RegistrationDetail commission(@PathVariable String id,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                             @Valid @RequestBody DeviceServiceInputs.CommissionRegistration body,
                                                             HttpServletRequest request) {
        var actor = security.require(request, "device:write", "management");
        return registrations.commission(actor, id, key, body, RequestContext.requestId(request));
    }

    @PostMapping("/api/management/device-registrations/{id}/abandon")
    public DeviceServiceModels.RegistrationDetail abandon(@PathVariable String id,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                          @Valid @RequestBody DeviceServiceInputs.AbandonRegistration body,
                                                          HttpServletRequest request) {
        var actor = security.require(request, "device:write", "management");
        return registrations.abandon(actor, id, key, body, RequestContext.requestId(request));
    }

    /** The exact permission is enforced inside the command service after the risk level is resolved. */
    private AuthService.Identity either(HttpServletRequest request, String audience, String... permissions) {
        AuthService.Identity identity = security.identity(request);
        if (!audience.equals(identity.audience())) throw ApiError.forbidden("登录入口与资源受众不匹配");
        for (String permission : permissions) if (identity.permissions().contains(permission)) return identity;
        throw ApiError.forbidden("没有此操作权限");
    }
}
