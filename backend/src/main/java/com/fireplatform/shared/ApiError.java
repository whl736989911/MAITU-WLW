package com.fireplatform.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

public class ApiError extends RuntimeException {
    public final int status;
    public final String code;
    public ApiError(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public static ApiError forbidden(String message) { return new ApiError(403, "FORBIDDEN", message); }
    public static ApiError bad(String message) { return new ApiError(400, "INVALID_REQUEST", message); }
    public static ApiError missing() { return new ApiError(404, "NOT_FOUND", "资源不存在"); }
    public static ApiError conflict(String code, String message) { return new ApiError(409, code, message); }
    public record Body(String code, String message, String requestId) { }
}

@RestControllerAdvice
class ApiExceptionHandler {
    private final AuditService audit;
    ApiExceptionHandler(AuditService audit) { this.audit = audit; }
    @ExceptionHandler(ApiError.class)
    ResponseEntity<ApiError.Body> api(ApiError e, HttpServletRequest request) {
        if (e.status >= 400 && e.status < 500) {
            audit.record(RequestContext.actorId(request), RequestContext.tenantId(request),
                "BUSINESS_REQUEST", null, "DENIED", RequestContext.requestId(request));
        }
        return ResponseEntity.status(e.status).body(new ApiError.Body(e.code, e.getMessage(), RequestContext.requestId(request)));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        ConstraintViolationException.class, HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError.Body> validation(Exception ignored, HttpServletRequest request) {
        if (request.getMethod().equals("POST") || request.getMethod().equals("PUT"))
            audit.record(RequestContext.actorId(request),RequestContext.tenantId(request),"INVALID_REQUEST",null,"DENIED",RequestContext.requestId(request));
        Throwable cause=ignored;
        for(int depth=0;cause!=null && depth<32;depth++,cause=cause.getCause())
            if(cause instanceof JsonRequestSizeFilter.BodyTooLarge) return ResponseEntity.status(413).body(new ApiError.Body("REQUEST_TOO_LARGE","JSON请求体超过资源限制",RequestContext.requestId(request)));
        return ResponseEntity.badRequest().body(new ApiError.Body("INVALID_REQUEST", "请求格式或参数无效", RequestContext.requestId(request)));
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ApiError.Body> notFound(Exception ignored, HttpServletRequest request) {
        return ResponseEntity.status(404).body(new ApiError.Body("NOT_FOUND", "资源不存在", RequestContext.requestId(request)));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError.Body> method(Exception ignored, HttpServletRequest request) {
        return ResponseEntity.status(405).body(new ApiError.Body("METHOD_NOT_ALLOWED", "请求方法不支持", RequestContext.requestId(request)));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError.Body> media(Exception ignored, HttpServletRequest request) {
        return ResponseEntity.status(415).body(new ApiError.Body("UNSUPPORTED_MEDIA_TYPE", "请使用JSON请求", RequestContext.requestId(request)));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError.Body> database(DataAccessException error, HttpServletRequest request) {
        // Log only the exception type and root cause text; never SQL values, tokens or credentials.
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class).warn("Data access failure request_id={} type={} cause={}",
                RequestContext.requestId(request), error.getClass().getSimpleName(),
                error.getMostSpecificCause() == null ? "" : error.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError.Body("STORAGE_UNAVAILABLE", "存储操作失败，请稍后重试", RequestContext.requestId(request)));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError.Body> unexpected(Exception ignored, HttpServletRequest request) {
        // Deliberately never log exception messages, bodies, Authorization, or credentials.
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class).warn("Unexpected API failure request_id={}", RequestContext.requestId(request));
        return ResponseEntity.status(500).body(new ApiError.Body("INTERNAL_ERROR", "服务内部错误", RequestContext.requestId(request)));
    }
}
