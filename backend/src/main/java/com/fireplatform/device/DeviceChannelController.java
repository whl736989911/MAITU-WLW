package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/management/device-channels")
public class DeviceChannelController {
    public record Create(@NotBlank @Size(max=64) String deviceId,@NotBlank @Size(max=64) String channelKey,@NotBlank @Size(max=120) String name,@NotBlank @Size(max=64) String channelType,@NotNull Boolean enabled,@NotBlank @Size(max=1000) String reason) { }
    public record Update(@NotBlank @Size(max=120) String name,@NotBlank @Size(max=64) String channelType,@NotNull Boolean enabled,@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=1000) String reason) { }
    private final RequestSecurity security;private final DeviceChannelService channels;
    public DeviceChannelController(RequestSecurity security,DeviceChannelService channels){this.security=security;this.channels=channels;}
    @GetMapping public DeviceChannelModels.Page list(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(name="device_id",required=false) String deviceId,@RequestParam(required=false) String keyword,HttpServletRequest request){var actor=security.require(request,"device:read","management");if(request.getParameterMap().entrySet().stream().anyMatch(e->!Set.of("page","size","device_id","keyword","scope_selected","scope_tenant_ids","scope_place_ids").contains(e.getKey())||e.getValue().length!=1))throw com.fireplatform.shared.ApiError.bad("通道查询参数无效或重复");return channels.list(actor.user().id(),security.optionalTenant(request),page,size,deviceId,keyword,security.readSelection(request));}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public DeviceChannelModels.Item create(@RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody Create body,HttpServletRequest request){var actor=security.require(request,"device:write","management");if(!request.getParameterMap().isEmpty())throw com.fireplatform.shared.ApiError.bad("通道登记不接受查询参数");return channels.create(actor,security.tenant(request),key,new DeviceChannelModels.Create(body.deviceId(),body.channelKey(),body.name(),body.channelType(),body.enabled(),body.reason()),RequestContext.requestId(request));}
    @PutMapping("/{id}") public DeviceChannelModels.Item update(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody Update body,HttpServletRequest request){var actor=security.require(request,"device:write","management");if(!request.getParameterMap().isEmpty())throw com.fireplatform.shared.ApiError.bad("通道修改不接受查询参数");return channels.update(actor,security.tenant(request),id,key,new DeviceChannelModels.Update(body.name(),body.channelType(),body.enabled(),body.expectedRevision(),body.reason()),RequestContext.requestId(request));}
}
