package com.fireplatform.processing;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/management")
public class ProcessingController {
    private final RequestSecurity security; private final ProcessingQueries queries;
    public ProcessingController(RequestSecurity security,ProcessingQueries queries) { this.security=security; this.queries=queries; }
    @GetMapping("/processing-tasks") public Page<ProcessingModels.Task> list(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) String status,HttpServletRequest request) { var actor=security.require(request,"tasks:read","management");return queries.list(actor.user().id(),security.optionalTenant(request),security.readSelection(request),page,size,keyword,status); }
    @GetMapping("/processing-tasks/{id}") public ProcessingModels.Detail detail(@PathVariable String id,HttpServletRequest request) { var actor=security.require(request,"tasks:read","management");return queries.detail(actor.user().id(),security.tenant(request),id); }
}
