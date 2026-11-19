package com.fireplatform.identity;

import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/units")
public class UnitManagementController {
    private final RequestSecurity security; private final UnitManagementService commands; private final UnitDirectoryQueries directory; private final UnitDirectoryCommands directoryCommands;
    public UnitManagementController(RequestSecurity security,UnitManagementService commands,UnitDirectoryQueries directory,UnitDirectoryCommands directoryCommands) { this.security=security; this.commands=commands; this.directory=directory; this.directoryCommands=directoryCommands; }
    @GetMapping public Page<UnitDirectoryModels.UnitDetails> list(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) String status,HttpServletRequest request) {
        var actor=security.require(request,"units:read","management"); return directory.list(actor.user().id(),page,size,keyword,status,security.readSelection(request));
    }
    @GetMapping("/{id}") public UnitDirectoryModels.UnitDetails detail(@PathVariable String id,HttpServletRequest request) { var actor=security.require(request,"units:read","management"); return directory.detail(actor.user().id(),id); }
    @PostMapping public UnitDirectoryModels.UnitDetails create(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody UnitDirectoryModels.UnitCreate body,HttpServletRequest request) {
        var actor=security.require(request,"units:write","management"); return directoryCommands.create(actor,key,body,RequestContext.requestId(request));
    }
    @PutMapping("/{id}") public IdentityQueries.Unit update(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ManagementInputs.UpdateUnit body,HttpServletRequest request) {
        var actor=security.require(request,"units:write","management"); return commands.update(actor,id,key,body,RequestContext.requestId(request));
    }
    @PutMapping("/{id}/details") public UnitDirectoryModels.UnitDetails updateDetails(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody UnitDirectoryModels.UnitWrite body,HttpServletRequest request) {
        var actor=security.require(request,"units:write","management");
        return directoryCommands.update(actor,id,key,body,RequestContext.requestId(request));
    }
    @PostMapping("/{id}/archive") public UnitDirectoryModels.UnitDetails archive(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IdentityLifecycleInput body,HttpServletRequest request) {
        var actor=security.require(request,"units:write","management"); return commands.archive(actor,id,key,body,RequestContext.requestId(request));
    }
    @DeleteMapping("/{id}") public UnitManagementService.LifecycleResult deleteIfEmpty(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody IdentityLifecycleInput body,HttpServletRequest request) {
        var actor=security.require(request,"units:write","management"); return commands.deleteIfEmpty(actor,id,key,body,RequestContext.requestId(request));
    }
}
