package com.fireplatform.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Covers Content-Length and chunked bodies before unbounded collection materialization. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+15)
public class JsonRequestSizeFilter extends OncePerRequestFilter {
    public static final class BodyTooLarge extends IOException { public BodyTooLarge() { super("JSON body resource limit"); } }
    private final ObjectMapper mapper;
    public JsonRequestSizeFilter(ObjectMapper mapper) { this.mapper=mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(!org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith("/api/") || !(request.getMethod().equals("POST") || request.getMethod().equals("PUT"))) { chain.doFilter(request,response); return; }
        if(request.getContentLengthLong()>Limits.MAX_JSON_BODY_BYTES) {
            response.setStatus(413); response.setContentType("application/json"); mapper.writeValue(response.getOutputStream(),new ApiError.Body("REQUEST_TOO_LARGE","JSON请求体超过资源限制",RequestContext.requestId(request))); return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream limited;
            @Override public ServletInputStream getInputStream() throws IOException {
                if(limited!=null) return limited;
                var delegate=super.getInputStream(); limited=new ServletInputStream() {
                    private long count;
                    private void add(int amount) throws IOException { if(amount>0 && (count+=amount)>Limits.MAX_JSON_BODY_BYTES) throw new BodyTooLarge(); }
                    @Override public int read() throws IOException { int value=delegate.read(); if(value>=0) add(1); return value; }
                    @Override public int read(byte[] buffer,int offset,int length) throws IOException { int amount=delegate.read(buffer,offset,(int)Math.min(length,Limits.MAX_JSON_BODY_BYTES-count+1)); add(amount); return amount; }
                    @Override public boolean isFinished() { return delegate.isFinished(); }
                    @Override public boolean isReady() { return delegate.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
                }; return limited;
            }
        },response);
    }
}
