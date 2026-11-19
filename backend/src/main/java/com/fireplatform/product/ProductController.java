package com.fireplatform.product;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProductController {
    private final RequestSecurity security; private final ProductQueries queries; private final ProductCommands commands;
    public ProductController(RequestSecurity security,ProductQueries queries,ProductCommands commands) { this.security=security; this.queries=queries; this.commands=commands; }
    @GetMapping("/api/management/products") public Page<ProductModels.Product> products(@RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) String state,HttpServletRequest request) { security.require(request,"products:read","management"); return queries.products(page,size,keyword,state); }
    @PostMapping("/api/management/products") public ProductModels.Product create(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProductInputs.CreateProduct body,HttpServletRequest request) { var actor=security.require(request,"products:write","management"); return commands.create(actor,key,body,RequestContext.requestId(request)); }
    @PutMapping("/api/management/products/{id}") public ProductModels.Product update(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProductInputs.UpdateProduct body,HttpServletRequest request) { var actor=security.require(request,"products:write","management"); return commands.update(actor,id,key,body,RequestContext.requestId(request)); }
    @PostMapping("/api/management/products/{id}/archive") public ProductModels.Product archive(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProductInputs.ProductLifecycle body,HttpServletRequest request) { var actor=security.require(request,"products:write","management"); return commands.archive(actor,id,key,body,RequestContext.requestId(request)); }
    @PostMapping("/api/management/products/{id}/reactivate") public ProductModels.Product reactivate(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProductInputs.ProductLifecycle body,HttpServletRequest request) { var actor=security.require(request,"products:write","management"); return commands.reactivate(actor,id,key,body,RequestContext.requestId(request)); }
    @GetMapping("/api/management/products/{id}/versions") public Map<String,List<ProductModels.Version>> versions(@PathVariable String id,HttpServletRequest request) { security.require(request,"products:read","management"); return Map.of("items",queries.versions(id)); }
    @PostMapping("/api/management/products/{id}/versions") public ProductModels.Version version(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProductInputs.CreateVersion body,HttpServletRequest request) { var actor=security.require(request,"products:write","management"); return commands.createVersion(actor,id,key,body,RequestContext.requestId(request)); }
    @PutMapping("/api/management/product-versions/{id}") public ProductModels.Version edit(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProductInputs.EditVersion body,HttpServletRequest request) { var actor=security.require(request,"products:write","management"); return commands.edit(actor,id,key,body,RequestContext.requestId(request)); }
    @PostMapping("/api/management/product-versions/{id}/test") public ProductModels.TestResult test(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProductInputs.TestVersion body,HttpServletRequest request) { var actor=security.require(request,"products:write","management"); return commands.test(actor,id,key,body,RequestContext.requestId(request)); }
    @PostMapping("/api/management/product-versions/{id}/publish") public ProductModels.Version publish(@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProductInputs.PublishVersion body,HttpServletRequest request) { var actor=security.require(request,"products:publish","management"); return commands.publish(actor,id,key,body,RequestContext.requestId(request)); }
}
