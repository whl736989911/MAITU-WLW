package com.fireplatform.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestMetadataFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String id=UUID.randomUUID().toString(); request.setAttribute(RequestContext.REQUEST_ID,id);
        response.setHeader("X-Request-Id",id); response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff"); response.setHeader("X-Frame-Options","DENY");
        chain.doFilter(request,response);
    }
}
