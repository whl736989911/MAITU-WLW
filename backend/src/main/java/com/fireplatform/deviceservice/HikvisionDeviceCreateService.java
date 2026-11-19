package com.fireplatform.deviceservice;

import com.fireplatform.identity.AuthService;
import org.springframework.stereotype.Service;

/** Orchestrates one confirmed Hikvision cloud add and local commission across independent transactions. */
@Service
public class HikvisionDeviceCreateService {
    private final DeviceRegistrationService registrations;

    public HikvisionDeviceCreateService(DeviceRegistrationService registrations) {
        this.registrations = registrations;
    }

    public DeviceServiceModels.RegistrationDetail create(AuthService.Identity actor, String tenant, String key,
            DeviceServiceInputs.CreateRegistration input, String requestId) {
        if (!"HIKVISION_FIRECLOUD".equals(input.driverKey()) || !"NEW_VENDOR".equals(input.mode()))
            throw com.fireplatform.shared.ApiError.bad("仅支持海康云厂商新增登记");
        if (tenant == null || tenant.isBlank() || !tenant.equals(input.tenantId()))
            throw com.fireplatform.shared.ApiError.bad("必须显式提供与X-Tenant-Id一致的单位");
        if (input.placeId() == null || input.placeId().isBlank())
            throw com.fireplatform.shared.ApiError.bad("海康云新增必须明确选择本地位置");

        DeviceServiceModels.RegistrationDetail detail = registrations.create(actor, tenant, key, input, requestId);
        var registration = detail.registration();
        if ("DRAFT".equals(registration.status()) && "NOT_ATTEMPTED".equals(registration.cloudState())) {
            detail = registrations.resume(actor, registration.id(), requestId);
            registration = detail.registration();
        }
        if ("CLOUD_REGISTERED".equals(registration.status())) {
            return registrations.commission(actor, registration.id(), key,
                new DeviceServiceInputs.CommissionRegistration(tenant, input.placeId(), input.productVersionId(), null,
                    null, "用户确认：海康云新增并本地建档；不代表物理激活或现场验收", input.deviceName(), input.location(), null, null), requestId);
        }
        return detail;
    }
}
