package com.fireplatform.deviceservice;

import com.fireplatform.shared.ApiError;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Single registry for PRODUCT_MODEL and VENDOR_PLATFORM drivers. No default/fallback driver exists. */
@Component
public class DriverRegistry {
    private final Map<String, IntegrationDriver> drivers = new LinkedHashMap<>();

    public DriverRegistry(List<IntegrationDriver> beans) {
        for (IntegrationDriver driver : beans) {
            if (drivers.putIfAbsent(driver.key(), driver) != null)
                throw new IllegalStateException("Duplicate integration driver key " + driver.key());
        }
    }

    @PostConstruct
    void verify() {
        if (drivers.isEmpty()) throw new IllegalStateException("No integration driver registered");
    }

    public List<IntegrationDriver> all() {
        return List.copyOf(drivers.values());
    }

    public IntegrationDriver find(String key) {
        return key == null ? null : drivers.get(key);
    }

    public IntegrationDriver require(String key) {
        IntegrationDriver driver = find(key);
        if (driver == null) throw new ApiError(409, "DRIVER_UNKNOWN", "未注册该接入驱动，不能下发或冒充成功");
        return driver;
    }
}
