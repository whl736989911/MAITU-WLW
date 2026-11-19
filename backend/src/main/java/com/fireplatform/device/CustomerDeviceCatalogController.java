package com.fireplatform.device;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Safe published-product metadata for customer device creation. Published schemas, parser code,
 * evidence blobs, drafts and product administration never cross this boundary.
 */
@RestController
@RequestMapping("/api/customer/device-catalog")
public class CustomerDeviceCatalogController {
    public record PublishedOption(String id, String productId, String productName, String manufacturer,
                                  String model, long versionNumber) { }
    private static final int MAX_SIZE = 100;
    private static final Set<String> KEYS = Set.of("page", "size", "keyword");
    private final RequestSecurity security;
    private final JdbcTemplate jdbc;
    private final PageSizePolicy sizes;

    public CustomerDeviceCatalogController(RequestSecurity security, JdbcTemplate jdbc, PageSizePolicy sizes) {
        this.security = security; this.jdbc = jdbc; this.sizes = sizes;
    }

    @GetMapping("/published-versions")
    public Page<PublishedOption> published(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(defaultValue = "") String keyword,
                                           HttpServletRequest request) {
        var identity = security.identity(request);
        if (!"customer".equals(identity.audience())
            || (!identity.permissions().contains("device:read") && !identity.permissions().contains("device:write")))
            throw ApiError.forbidden("没有此操作权限");
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !KEYS.contains(entry.getKey()) || entry.getValue().length != 1))
            throw ApiError.bad("产品版本查询参数无效或重复");
        if (page < 1 || page > Limits.MAX_PAGE) throw ApiError.bad("页码无效");
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        if (pagination.size() > MAX_SIZE) throw ApiError.bad("分页条数无效");
        int resolved = pagination.size();
        if (keyword == null) keyword = "";
        if (keyword.length() > Limits.MAX_KEYWORD_LENGTH) throw ApiError.bad("搜索关键字过长");
        String pattern = "%" + keyword.toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM product_versions v JOIN products p ON p.id=v.product_id WHERE v.status='PUBLISHED' AND (LOWER(p.name) LIKE ? ESCAPE '!' OR LOWER(p.model) LIKE ? ESCAPE '!' OR LOWER(p.manufacturer) LIKE ? ESCAPE '!')",
            Long.class, pattern, pattern, pattern);
        List<PublishedOption> items = jdbc.query("SELECT v.id,p.id,p.name,p.manufacturer,p.model,v.version_number FROM product_versions v JOIN products p ON p.id=v.product_id WHERE v.status='PUBLISHED' AND (LOWER(p.name) LIKE ? ESCAPE '!' OR LOWER(p.model) LIKE ? ESCAPE '!' OR LOWER(p.manufacturer) LIKE ? ESCAPE '!') ORDER BY p.name,p.model,v.version_number DESC,p.id,v.id LIMIT ? OFFSET ?",
            (rs, n) -> new PublishedOption(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)),
            pattern, pattern, pattern, resolved, pagination.offset());
        return new Page<>(items, total, pagination.page(), resolved);
    }
}
