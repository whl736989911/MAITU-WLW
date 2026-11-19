package com.fireplatform.ingestion;

import com.fireplatform.identity.*;
import com.fireplatform.shared.IdempotencyStore;
import org.springframework.stereotype.Service;

@Service
public class IngestionGuard {
    private final ManagementGuard management; private final TenantFence tenants; private final IdempotencyStore idem;
    public IngestionGuard(ManagementGuard management,TenantFence tenants,IdempotencyStore idem) { this.management=management; this.tenants=tenants; this.idem=idem; }
    public void begin(AuthService.Identity actor,String tenant,String permission,String key,boolean deviceWrite) {
        management.begin(actor,permission,tenant,"management");
        if(deviceWrite) management.requireCurrent(actor,"device:write",tenant,"management");
        tenants.lockEnabled(tenant); idem.validateKey(key);
    }
    public boolean raw(AuthService.Identity actor) { return management.hasCurrentPermission(actor,"messages:raw"); }
}
