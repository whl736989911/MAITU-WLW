package com.fireplatform.identity;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String RESUME_HEADER = "X-Customer-Session";
    private static final String RESUME_COOKIE = "__Secure-fire-customer-resume";
    private static final String TAB_FORMAT = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private final AuthService auth; private final RequestSecurity security;
    public AuthController(AuthService auth,RequestSecurity security) { this.auth=auth; this.security=security; }
    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody AuthService.LoginRequest body, HttpServletRequest request,
                                         HttpServletResponse response) {
        String tab = "customer".equals(body.audience()) ? request.getHeader(RESUME_HEADER) : null;
        if (tab != null) requireBrowserTab(tab, request);
        var result = auth.login(body, RequestContext.requestId(request));
        if (tab != null) {
            if (result.user().mustChangePassword()) setResumeCookie(response, "", true);
            else setResumeCookie(response, tab + "." + result.accessToken(), false);
        }
        return result;
    }

    @PostMapping("/customer-session")
    public AuthService.LoginResult resume(HttpServletRequest request, HttpServletResponse response) {
        String tab = request.getHeader(RESUME_HEADER);
        requireBrowserTab(tab, request);
        String cookie = resumeCookie(request);
        // A different tab's expired marker must not remove the current tab's cookie.
        if (cookie == null || !cookie.startsWith(tab + ".")) throw AuthService.unauthorized();
        String bearer = cookie.substring(tab.length() + 1);
        try {
            var identity = auth.authenticate(bearer);
            if (!"customer".equals(identity.audience()) || identity.user().mustChangePassword())
                throw AuthService.unauthorized();
            return new AuthService.LoginResult(bearer, identity.user(), identity.permissions());
        } catch (ApiError error) {
            if (error.status == 401 || error.status == 403) setResumeCookie(response, "", true);
            throw error;
        }
    }
    @GetMapping("/me") public AuthService.SessionView me(HttpServletRequest request) { var identity=security.identity(request); return new AuthService.SessionView(identity.user(),identity.permissions()); }
    @PostMapping("/change-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@Valid @RequestBody AuthService.ChangePasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
        var identity = security.identity(request);
        auth.changePassword(identity, body, RequestContext.requestId(request));
        clearMatchingCookie(identity, request, response);
    }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        var identity = security.identity(request);
        auth.logout(identity, RequestContext.requestId(request));
        clearMatchingCookie(identity, request, response);
    }

    private static void requireBrowserTab(String tab, HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !"same-origin".equals(site)) throw ApiError.forbidden("只允许从本网站恢复用户会话");
        if (tab == null || !tab.matches(TAB_FORMAT)) throw AuthService.unauthorized();
    }

    private static String resumeCookie(HttpServletRequest request) {
        String value = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) if (RESUME_COOKIE.equals(cookie.getName())) {
            if (value != null) return null;
            value = cookie.getValue();
        }
        return value;
    }

    private static void clearMatchingCookie(AuthService.Identity identity, HttpServletRequest request, HttpServletResponse response) {
        if (!"customer".equals(identity.audience())) return;
        String cookie = resumeCookie(request);
        String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (cookie != null && bearer != null && cookie.endsWith("." + bearer.substring(7)))
            setResumeCookie(response, "", true);
    }

    private static void setResumeCookie(HttpServletResponse response, String value, boolean clear) {
        var cookie = ResponseCookie.from(RESUME_COOKIE, value).path("/api/auth")
            .secure(true).httpOnly(true).sameSite("Strict");
        if (clear) cookie.maxAge(0);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }
}
