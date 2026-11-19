package com.fireplatform.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/** Configuration owns role/readiness gates, so identity has no dependency on configuration. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RuntimeGateFilter extends OncePerRequestFilter {
    private final RuntimeConfig config;
    private final ApplicationAvailability availability;
    private final ObjectMapper mapper;
    public RuntimeGateFilter(RuntimeConfig config,ApplicationAvailability availability,ObjectMapper mapper) { this.config=config; this.availability=availability; this.mapper=mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!path.startsWith("/api/") || (path.equals("/api/health") && request.getMethod().equals("GET"))) { chain.doFilter(request,response); return; }
        String code=null; String message=null;
        if (availability.getReadinessState()!=ReadinessState.ACCEPTING_TRAFFIC) { code="NOT_READY"; message="初始化尚未提交，请稍后重试"; }
        else if (!config.apiEnabled()) { code="ROLE_NOT_IMPLEMENTED"; message="本机运行角色不开放管理/捕获API，请查看health中的本机消费者能力"; }
        if (code!=null) {
            response.setStatus(503); response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(),new ApiError.Body(code,message,RequestContext.requestId(request))); return;
        }
        chain.doFilter(request,response);
    }
}
