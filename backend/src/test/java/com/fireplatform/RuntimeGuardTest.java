package com.fireplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.configuration.RuntimeGateFilter;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeGuardTest {
    private MockEnvironment environment(String profile,String storage,String url) {
        var env=new MockEnvironment().withProperty("app.storage",storage).withProperty("spring.datasource.url",url); env.setActiveProfiles(profile); return env;
    }
    @Test void h2NeverProduction() {
        assertThatThrownBy(()->new RuntimeConfig(environment("prod","h2-dev","jdbc:h2:mem:x"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new RuntimeConfig(environment("prod","postgresql","jdbc:h2:mem:x"))).isInstanceOf(IllegalStateException.class);
        assertThat(new RuntimeConfig(environment("prod","postgresql","jdbc:postgresql://localhost/fire")).storage()).isEqualTo("postgresql");
        var mixed=environment("dev","h2-dev","jdbc:h2:mem:x"); mixed.setActiveProfiles("dev","prod"); assertThatThrownBy(()->new RuntimeConfig(mixed)).isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidRolesFailAndWorkerIngestNeverExposeBusinessEndpoints() throws Exception {
        for(String role:List.of("all,worker","invalid","","api,")) assertThatThrownBy(()->new RuntimeConfig(environment("dev","h2-dev","jdbc:h2:mem:x").withProperty("app.roles",role))).isInstanceOf(IllegalStateException.class);
        for(String role:List.of("worker","ingest","worker,ingest")) {
            var config=new RuntimeConfig(environment("dev","h2-dev","jdbc:h2:mem:x").withProperty("app.roles",role)); assertThat(config.apiEnabled()).isFalse();
            var availability=mock(ApplicationAvailability.class); when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
            var filter=new RuntimeGateFilter(config,availability,new ObjectMapper()); var chain=mock(FilterChain.class); var response=new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST","/api/auth/login"),response,chain); assertThat(response.getStatus()).isEqualTo(503); verifyNoInteractions(chain);
            var publicChain=mock(FilterChain.class); filter.doFilter(new MockHttpServletRequest("GET","/api/health"),new MockHttpServletResponse(),publicChain); verify(publicChain).doFilter(any(),any());
            var matrixChain=mock(FilterChain.class); var matrixResponse=new MockHttpServletResponse(); filter.doFilter(new MockHttpServletRequest("POST","/api;ignored/auth/login"),matrixResponse,matrixChain); assertThat(matrixResponse.getStatus()).isEqualTo(503); verifyNoInteractions(matrixChain);
        }
    }
}
