package com.fireplatform.device;

import com.fireplatform.shared.DeviceAccessScope;
import com.fireplatform.shared.ApiError;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeviceWorkbenchMetadataFilterTest {
    @Test void exactNullableMetadataFiltersRemainDistinctFromUnclassifiedRows() {
        var filter=DeviceWorkbenchFilter.parse(null,null,null,null," Fire system ","Smoke detector");
        var plan=filter.plan("SELECT d.* FROM devices d",null,OffsetDateTime.parse("2026-11-06T00:00:00Z"),new DeviceAccessScope.Filter("1=1",List.of()));
        assertThat(plan.fromWhere()).contains("q.system_type=?","q.device_category=?");
        assertThat(plan.bindings()).contains("Fire system","Smoke detector");
        assertThat(DeviceWorkbenchFilter.parse(null,null,null,null,null,null).systemType()).isNull();
        assertThat(DeviceWorkbenchFilter.parse(null,null,null,null,null,null).deviceCategory()).isNull();
    }
    @Test void unsafeOrOverlongManualMetadataQueryValuesAreRejected() {
        assertThatThrownBy(()->DeviceWorkbenchFilter.parse(null,null,null,null,"bad\nvalue",null)).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->DeviceWorkbenchFilter.parse(null,null,null,null,null,"x".repeat(81))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->DeviceWorkbenchFilter.parse(null,null,null,null,"  ",null)).isInstanceOf(ApiError.class);
    }
}
