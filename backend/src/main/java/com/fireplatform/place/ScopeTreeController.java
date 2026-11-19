package com.fireplatform.place;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScopeTreeController {
    private final RequestSecurity security;
    private final ScopeTreeQueries queries;
    public ScopeTreeController(RequestSecurity security, ScopeTreeQueries queries) { this.security = security; this.queries = queries; }

    @GetMapping("/api/management/scope-tree")
    public List<ScopeTreeQueries.UnitNode> tree(HttpServletRequest request) {
        var identity = security.identity(request);
        if (!identity.audience().equals("management")) throw ApiError.forbidden("仅管理端可读取单位位置树");
        return queries.tree(identity.user().id());
    }
}
