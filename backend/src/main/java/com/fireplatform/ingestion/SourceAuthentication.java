package com.fireplatform.ingestion;

import com.fireplatform.configuration.RuntimeConfig;
import com.fireplatform.identity.TenantFence;
import com.fireplatform.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SourceAuthentication {
    private record Credential(String tenant,String hash,boolean enabled) { }
    private final JdbcTemplate jdbc; private final TenantFence tenants; private final RuntimeConfig config; private final SourceQueries sources;
    public SourceAuthentication(JdbcTemplate jdbc,TenantFence tenants,RuntimeConfig config,SourceQueries sources) { this.jdbc=jdbc; this.tenants=tenants; this.config=config; this.sources=sources; }
    public static ApiError denied() { return new ApiError(401,"SOURCE_UNAUTHORIZED","接入凭据无效或接收不可用"); }
    public void captureEnabled() { if(!config.genericIngestEnabled()) throw new ApiError(503,"GENERIC_INGEST_DISABLED","通用捕获尚未启用"); }
    private String token(String authorization) { if(authorization==null || !authorization.matches("Bearer gih_[A-Za-z0-9_-]{43}")) throw denied(); return authorization.substring(7); }
    private Credential credential(String id,boolean lock) {
        var rows=jdbc.query("SELECT tenant_id,token_hash,enabled FROM ingestion_sources WHERE id=?"+(lock ? " FOR UPDATE" : ""),(rs,n)->new Credential(rs.getString(1),rs.getString(2),rs.getBoolean(3)),id); if(rows.isEmpty()) throw denied(); return rows.getFirst();
    }
    private void match(Credential source,String token) { if(!source.enabled() || !MessageDigest.isEqual(Digests.sha256(token).getBytes(StandardCharsets.US_ASCII),source.hash().getBytes(StandardCharsets.US_ASCII))) throw denied(); }
    /** Early, non-authoritative check before Jackson consumes a body. The transaction repeats everything. */
    public void initial(String source,String authorization) { captureEnabled(); String token=token(authorization); var row=credential(source,false); match(row,token); if(!tenants.enabled(row.tenant())) throw denied(); }
    /** Caller transaction: nonlocking tenant discovery -> tenant fence -> source lock -> recheck credential/state. */
    public IngestionModels.Source lock(String source,String authorization) {
        captureEnabled(); String token=token(authorization); var discovered=credential(source,false);
        try { tenants.lockEnabled(discovered.tenant()); } catch(ApiError e) { throw denied(); }
        var locked=credential(source,true); if(!discovered.tenant().equals(locked.tenant())) throw denied(); match(locked,token);
        return sources.sourceSystem(locked.tenant(),source,false);
    }
}
