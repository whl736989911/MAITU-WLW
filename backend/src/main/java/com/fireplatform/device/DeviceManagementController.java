package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/management/devices")
public class DeviceManagementController {
    private final RequestSecurity security; private final DeviceManagementService commands;
    public DeviceManagementController(RequestSecurity security,DeviceManagementService commands) { this.security=security; this.commands=commands; }
    @PostMapping public DeviceQueryService.DeviceRow register(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DeviceInputs.Register body,HttpServletRequest request) { var actor=security.require(request,"device:write","management"); return commands.register(actor,security.tenant(request),key,body,RequestContext.requestId(request),"management"); }
    @PutMapping("/{id}") public DeviceQueryService.DeviceRow edit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DeviceInputs.Edit body,HttpServletRequest request) { var actor=security.require(request,"device:write","management"); return commands.edit(actor,security.tenant(request),id,key,body,RequestContext.requestId(request),"management"); }
    @PostMapping("/{id}/migrate-version") public DeviceQueryService.DeviceRow migrate(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DeviceInputs.Migrate body,HttpServletRequest request) { var actor=security.require(request,"device:migrate","management"); return commands.migrate(actor,security.tenant(request),id,key,body,RequestContext.requestId(request)); }
    @PostMapping("/batch-migrate-version") public DeviceManagementService.BatchResult batchMigrate(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DeviceInputs.BatchMigrate body,HttpServletRequest request) { var actor=security.require(request,"device:migrate","management"); return commands.batchMigrate(actor,security.tenant(request),key,body,RequestContext.requestId(request)); }
    @PostMapping("/batch-move") public DeviceManagementService.BatchResult batchMove(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody DeviceInputs.BatchMove body,HttpServletRequest request) { var actor=security.require(request,"device:write","management"); return commands.batchMove(actor,security.tenant(request),key,body,RequestContext.requestId(request),"management"); }
    @GetMapping("/{id}/version-history") public Map<String,List<DeviceManagementService.History>> history(@PathVariable String id,HttpServletRequest request) { var actor=security.require(request,"device:read","management"); return Map.of("items",commands.history(actor,security.tenant(request),id,"management")); }
}
