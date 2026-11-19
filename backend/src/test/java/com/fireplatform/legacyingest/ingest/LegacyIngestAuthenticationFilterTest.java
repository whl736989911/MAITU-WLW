package com.fireplatform.legacyingest.ingest;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.legacyingest.web.LegacyIngestAuthenticationFilter;
import jakarta.servlet.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.mock.web.MockFilterChain;

class LegacyIngestAuthenticationFilterTest {
    @Test void wrongTokenIsForbidden()throws Exception{var response=invoke("secret","wrong",null,null);assertEquals(403,response.getStatus());assertEquals("LEGACY_INGEST_DENIED",new ObjectMapper().readTree(response.getContentAsByteArray()).path("code").asText());}
    @Test void missingConfiguredTokenIsUnavailable()throws Exception{var response=invoke("","secret",null,null);assertEquals(503,response.getStatus());assertEquals("LEGACY_INGEST_DISABLED",new ObjectMapper().readTree(response.getContentAsByteArray()).path("code").asText());}
    @Test void authorizedFormttlRequiresNoQueryOrTenantOverride()throws Exception{var response=invoke("secret","secret",null,null);assertEquals(200,response.getStatus());var query=invoke("secret","secret","x=1",null);assertEquals(403,query.getStatus());var tenant=invoke("secret","secret",null,"tenant");assertEquals(403,tenant.getStatus());}
    private MockHttpServletResponse invoke(String configured,String supplied,String query,String tenant)throws ServletException,IOException{var request=new MockHttpServletRequest("POST","/v1/formttl/heartbeat/telemetry");request.setQueryString(query);if(supplied!=null)request.addHeader("X-Legacy-Token",supplied);if(tenant!=null)request.addHeader("X-Tenant-Id",tenant);var response=new MockHttpServletResponse();var filter=new LegacyIngestAuthenticationFilter(configured,new ObjectMapper());filter.doFilter(request,response,new MockFilterChain());return response;}
}
