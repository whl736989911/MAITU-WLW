package com.fireplatform.device;

import com.fireplatform.shared.ApiError;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeviceListFilterTest {
    @Test void sourceStatusAxesRemainIndependentAndManualMetadataNullStaysUnknown(){
        var filter=DeviceListFilter.parse("UNKNOWN","FIRE","PENDING_INTEGRATION","Fire","Smoke sensor");
        assertThat(filter.connectivity()).isEqualTo("UNKNOWN");
        assertThat(filter.physicalState()).isEqualTo("FIRE");
        assertThat(filter.integrationState()).isEqualTo("PENDING_INTEGRATION");
        assertThat(filter.systemType()).isEqualTo("Fire");
        assertThat(filter.deviceCategory()).isEqualTo("Smoke sensor");
        var unclassified=DeviceListFilter.parse(null,null,null,null,null);
        assertThat(unclassified.systemType()).isNull();
        assertThat(unclassified.deviceCategory()).isNull();
    }
    @Test void invalidStatusValuesAreRejected(){
        assertThatThrownBy(()->DeviceListFilter.parse("offline",null,null,null,null)).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->DeviceListFilter.parse(null,"FIRE","NORMAL",null,null)).isInstanceOf(ApiError.class);
    }
}
