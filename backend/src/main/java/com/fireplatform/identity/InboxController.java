package com.fireplatform.identity;

import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

/** N13：个人中心与站内消息（自助接口）+ 平台投递。 */
@RestController
public class InboxController {
    private final RequestSecurity security; private final InboxService inbox;
    public InboxController(RequestSecurity security,InboxService inbox) { this.security=security; this.inbox=inbox; }

    public record Rename(@NotBlank @Size(max=200) String name) { }
    public record StateChange(@NotNull @Min(0) Long expected_revision) { }
    public record Publish(@NotBlank String kind,@NotBlank @Size(max=200) String title,@NotBlank @Size(max=2000) String body,@NotBlank @Size(max=1000) String reason) { }

    private AuthService.Identity self(HttpServletRequest request,String permission) {
        var identity=security.identity(request);
        if(!identity.permissions().contains(permission)) throw ApiError.forbidden("没有此操作权限");
        return identity;
    }

    @GetMapping("/api/me") public InboxService.Profile me(HttpServletRequest request) { return inbox.profile(self(request,"inbox:read")); }

    @PutMapping("/api/me/profile") public InboxService.Profile rename(@Valid @RequestBody Rename body,HttpServletRequest request) { return inbox.rename(self(request,"inbox:read"),body.name()); }

    @GetMapping("/api/me/sessions") public java.util.List<InboxService.Session> sessions(HttpServletRequest request) { return inbox.sessions(self(request,"inbox:read")); }

    @PostMapping("/api/me/sessions/revoke-others") public java.util.Map<String,Object> revokeOthers(HttpServletRequest request) { return java.util.Map.of("revoked",inbox.revokeOtherSessions(self(request,"inbox:read"))); }

    @GetMapping("/api/me/messages") public Page<InboxService.Message> messages(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String status,@RequestParam(required=false) String keyword,@RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpServletRequest request) {
        return inbox.messages(self(request,"inbox:read"),page,size,status,keyword,messageTime(from),messageTime(to));
    }
    private static java.time.OffsetDateTime messageTime(String value) {
        if(value==null||value.isEmpty()) return null;
        try { return java.time.OffsetDateTime.parse(value); }
        catch(java.time.DateTimeException e) { throw ApiError.bad("消息时间需要明确时区"); }
    }

    @PostMapping("/api/me/messages/{id}/read") public InboxService.Message read(@PathVariable String id,@Valid @RequestBody StateChange body,HttpServletRequest request) {
        return inbox.read(self(request,"inbox:read"),id,body.expected_revision());
    }

    @PostMapping("/api/me/messages/{id}/handle") public InboxService.Message handle(@PathVariable String id,@Valid @RequestBody StateChange body,HttpServletRequest request) {
        return inbox.handle(self(request,"inbox:handle"),id,body.expected_revision());
    }

    @PostMapping("/api/management/user-messages/publish") public java.util.Map<String,Object> publish(@Valid @RequestBody Publish body,HttpServletRequest request) {
        var identity=security.require(request,"inbox:publish","management");
        int delivered=inbox.publish(identity,security.tenant(request),body.kind(),body.title(),body.body(),body.reason());
        return java.util.Map.of("delivered",delivered);
    }
}
