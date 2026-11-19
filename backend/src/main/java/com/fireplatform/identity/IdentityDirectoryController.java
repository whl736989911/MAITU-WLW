package com.fireplatform.identity;

import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class IdentityDirectoryController {
    @GetMapping("/api/management/roles/{roleId}/users")
    public Page<IdentityDirectoryService.AssignedUser> roleUsers(@PathVariable String roleId,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,HttpServletRequest request){ var actor=security.require(request,"roles:read","management"); return directory.assignedUsers(actor.user().id(),roleId,page,size); }
    private final RequestSecurity security; private final IdentityDirectoryService directory;
    public IdentityDirectoryController(RequestSecurity security,IdentityDirectoryService directory){this.security=security;this.directory=directory;}
    @GetMapping("/api/{audience:management|customer}/identity/departments")
    public java.util.List<IdentityDirectoryService.Department> departments(@PathVariable String audience,HttpServletRequest request){ security.require(request,"users:read",audience); String tenant=security.tenant(request); if(!request.getParameterMap().isEmpty())throw com.fireplatform.shared.ApiError.bad("不支持部门查询参数"); return directory.departments(tenant); }
    @PostMapping("/api/{audience:management|customer}/identity/departments")
    public IdentityDirectoryService.Department createDepartment(@PathVariable String audience,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IdentityDirectoryInputs.Department body,HttpServletRequest request){ var actor=security.require(request,"users:write",audience); String tenant=security.tenant(request); return directory.saveDepartment(actor,tenant,null,key,body,RequestContext.requestId(request)); }
    @PutMapping("/api/{audience:management|customer}/identity/departments/{id}")
    public IdentityDirectoryService.Department updateDepartment(@PathVariable String audience,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IdentityDirectoryInputs.Department body,HttpServletRequest request){ var actor=security.require(request,"users:write",audience); String tenant=security.tenant(request); return directory.saveDepartment(actor,tenant,id,key,body,RequestContext.requestId(request)); }
    @GetMapping("/api/{audience:management|customer}/identity/users")
    public Page<IdentityDirectoryService.UserProfile> users(@PathVariable String audience,@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false,name="department_id") String departmentId,@RequestParam(required=false) Boolean enabled,HttpServletRequest request){ var actor=security.require(request,"users:read",audience); String tenant=actor.user().userType().equals("PLATFORM")?security.optionalTenant(request):actor.user().tenantId(); return directory.users(actor,tenant,security.readSelection(request),page,size,keyword,departmentId,enabled); }
    @PutMapping("/api/{audience:management|customer}/identity/users/{id}")
    public IdentityDirectoryService.UserProfile updateUser(@PathVariable String audience,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IdentityDirectoryInputs.UserMetadata body,HttpServletRequest request){ var actor=security.require(request,"users:write",audience); String tenant=security.tenant(request); return directory.saveMetadata(actor,tenant,id,key,body,RequestContext.requestId(request)); }
}
