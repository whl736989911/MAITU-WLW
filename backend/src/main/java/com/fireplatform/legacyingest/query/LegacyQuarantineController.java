package com.fireplatform.legacyingest.query;

import com.fireplatform.identity.RequestSecurity;
import com.fireplatform.shared.Pagination;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Explicit global integration-operations permission; never exposed as a customer's device facts. */
@RestController
@RequestMapping("/api/management/legacy-ingest/quarantines")
public class LegacyQuarantineController {
    public record Row(String id,String vendorPlatform,String vendorDeviceId,OffsetDateTime receivedAt,
        OffsetDateTime observedAt,String reason,String candidateDeviceId) { }
    public record Page(List<Row> items,long total,int page,int size) { }
    private final RequestSecurity security;
    private final JdbcTemplate jdbc;
    public LegacyQuarantineController(RequestSecurity security,JdbcTemplate jdbc){this.security=security;this.jdbc=jdbc;}
    @GetMapping
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request) {
        security.require(request,"ownership-exceptions:read","management");
        var p=Pagination.of(page,size,20);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM legacy_ingest_quarantines",Long.class);
        var rows=jdbc.query("SELECT m.id,m.vendor_platform,m.vendor_device_id,m.received_at,m.observed_at,q.reason,q.candidate_device_id FROM legacy_ingest_quarantines q JOIN legacy_ingest_messages m ON m.id=q.message_id ORDER BY q.recorded_at DESC,m.id LIMIT ? OFFSET ?",
            (r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,OffsetDateTime.class),r.getObject(5,OffsetDateTime.class),r.getString(6),r.getString(7)),p.size(),p.offset());
        return new Page(rows,total,p.page(),p.size());
    }
}
