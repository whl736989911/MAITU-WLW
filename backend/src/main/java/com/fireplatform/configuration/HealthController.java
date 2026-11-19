package com.fireplatform.configuration;

import java.util.Map;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final RuntimeConfig config;
    private final ApplicationAvailability availability; private final com.fireplatform.shared.NotificationStorageReadiness notificationStorage;
    public HealthController(RuntimeConfig config, ApplicationAvailability availability,com.fireplatform.shared.NotificationStorageReadiness notificationStorage) { this.config=config; this.availability=availability; this.notificationStorage=notificationStorage; }
    @GetMapping("/api/health")
    public ResponseEntity<Map<String,Object>> health() {
        boolean ready=availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC;
        var body=new java.util.LinkedHashMap<String,Object>(Map.of("status",ready ? "UP" : "STARTING", "storage_mode",config.storage(),
            "generic_ingest_enabled",config.genericIngestEnabled(),"roles",config.roles(),
            "generic_message_worker_enabled",config.genericMessageWorkerEnabled(),
            "role_capabilities",Map.of("api",config.apiEnabled(),"ingest",config.roles().contains("all")||config.roles().contains("ingest"),"worker",config.roles().contains("all")||config.roles().contains("worker")),
            "deployment_stage","single-node"));
        body.put("contact_storage_ready",notificationStorage.contactStorageReady());
        return ResponseEntity.status(ready ? 200 : 503).body(body);
    }
}
