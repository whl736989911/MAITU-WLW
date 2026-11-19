package com.fireplatform.legacyingest.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class LegacyIngestAuthenticationFilter extends OncePerRequestFilter {
    private final String configuredToken;private final ObjectMapper mapper;
    public LegacyIngestAuthenticationFilter(@Value("${fire.legacy.ingest-token:}") String configuredToken,ObjectMapper mapper){this.configuredToken=configuredToken;this.mapper=mapper;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request);return !request.getMethod().equals("POST")||!(path.matches("/api/notify/v1/[^/]+/[^/]+/telemetry")||path.matches("/v1/formttl/[^/]+/telemetry"));}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request);if(configuredToken==null||configuredToken.isBlank()){deny(response,503,"LEGACY_INGEST_DISABLED",request);return;}String supplied=path.startsWith("/api/notify/")?path.split("/",6)[4]:request.getHeader("X-Legacy-Token");boolean equal=supplied!=null&&MessageDigest.isEqual(configuredToken.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));if(!equal||request.getQueryString()!=null||request.getHeader("X-Tenant-Id")!=null){deny(response,403,"LEGACY_INGEST_DENIED",request);return;}chain.doFilter(request,response);
    }
    private void deny(HttpServletResponse response,int status,String code,HttpServletRequest request)throws IOException{response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding(StandardCharsets.UTF_8.name());mapper.writeValue(response.getOutputStream(),new ApiError.Body(code,"旧平台接入认证失败",RequestContext.requestId(request)));}
}
