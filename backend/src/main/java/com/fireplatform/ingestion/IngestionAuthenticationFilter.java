package com.fireplatform.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+25)
public class IngestionAuthenticationFilter extends OncePerRequestFilter {
    private static final Pattern PATH=Pattern.compile("/api/ingest/([A-Za-z0-9_-]{1,64})/messages");
    private final SourceAuthentication authentication; private final ObjectMapper mapper;
    public IngestionAuthenticationFilter(SourceAuthentication authentication,ObjectMapper mapper) { this.authentication=authentication; this.mapper=mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        var matcher=PATH.matcher(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
        if(!request.getMethod().equals("POST") || !matcher.matches()) { chain.doFilter(request,response); return; }
        try {
            authentication.initial(matcher.group(1),request.getHeader("Authorization"));
            if(request.getQueryString()!=null || request.getHeader("X-Tenant-Id")!=null) throw ApiError.bad("接收端不接受query或租户头");
            chain.doFilter(request,response);
        } catch(ApiError e) { error(response,request,e.status,e.code,e.getMessage()); }
        catch(DataAccessException e) { error(response,request,503,"STORAGE_UNAVAILABLE","存储操作失败，请稍后重试"); }
    }
    private void error(HttpServletResponse response,HttpServletRequest request,int status,String code,String message) throws IOException { response.setStatus(status); response.setContentType("application/json"); mapper.writeValue(response.getOutputStream(),new ApiError.Body(code,message,RequestContext.requestId(request))); }
}
