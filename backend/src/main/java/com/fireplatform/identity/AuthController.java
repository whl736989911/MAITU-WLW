package com.fireplatform.identity;

import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth; private final RequestSecurity security;
    public AuthController(AuthService auth,RequestSecurity security) { this.auth=auth; this.security=security; }
    @PostMapping("/login") public AuthService.LoginResult login(@Valid @RequestBody AuthService.LoginRequest body,HttpServletRequest request) { return auth.login(body,RequestContext.requestId(request)); }
    @GetMapping("/me") public AuthService.SessionView me(HttpServletRequest request) { var identity=security.identity(request); return new AuthService.SessionView(identity.user(),identity.permissions()); }
    @PostMapping("/change-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@Valid @RequestBody AuthService.ChangePasswordRequest body,HttpServletRequest request) { auth.changePassword(security.identity(request),body,RequestContext.requestId(request)); }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) { auth.logout(security.identity(request),RequestContext.requestId(request)); }
}
