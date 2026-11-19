package com.fireplatform.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Final JSON output and authorization revocation share the same short coordination fence. */
@Component
public class AuthorizedJsonConverter extends MappingJackson2HttpMessageConverter {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final RequestSecurity security;
    private final TransactionTemplate transaction;

    public AuthorizedJsonConverter(ObjectMapper mapper, JdbcTemplate jdbc, AuthService auth,
                                   RequestSecurity security, PlatformTransactionManager transactions) {
        super(mapper);
        this.jdbc = jdbc; this.auth = auth; this.security = security;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setName("authorized-json-output");
    }

    @Override protected void writeInternal(Object body, Type type, HttpOutputMessage output) throws IOException {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            super.writeInternal(body, type, output);
            return;
        }
        HttpServletRequest request = servlet.getRequest();
        String path = request.getRequestURI();
        if (!"GET".equals(request.getMethod()) || !path.startsWith("/api/") || path.equals("/api/health") || body instanceof ApiError.Body) {
            super.writeInternal(body, type, output);
            return;
        }
        var initial = security.identity(request);
        try {
            transaction.executeWithoutResult(status -> {
                // No query transaction remains here. Security writers take this row before any actor/session row.
                jdbc.queryForObject("SELECT id FROM security_management_lock WHERE id=1 FOR UPDATE", Integer.class);
                var users = jdbc.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", String.class, initial.user().id());
                if (users.isEmpty()) throw AuthService.unauthorized();
                jdbc.queryForList("SELECT token_hash FROM sessions WHERE token_hash=? AND user_id=? FOR UPDATE",
                    String.class, initial.tokenHash(), initial.user().id());
                var current = auth.refreshKnownIdentity(initial.tokenHash());
                if (!current.permissions().equals(initial.permissions())) throw ApiError.forbidden("操作权限已变化，请重新核验会话");
                if (path.matches("^/api/management/devices/[^/]+/battery-low-threshold(?:/history)?$") &&
                    !current.permissions().contains("parameters:read")) throw ApiError.forbidden("没有此操作权限");
                if (request.getHeader("X-Tenant-Id") != null) security.tenant(request);
                try {
                    // Keep the fence until the bytes are serialized, not merely until a pre-write callback returns.
                    super.writeInternal(body, type, output);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }
}
