package com.fireplatform.deviceservice;

import com.fireplatform.configuration.RuntimeConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background dispatcher for durable queued service commands. It only runs on worker-capable role
 * instances and never retries an UNKNOWN outcome.
 */
@Component
public class DeviceServiceWorker {
    private final DeviceServiceCommandService commands;
    private final RuntimeConfig runtime;
    private final int batch;

    public DeviceServiceWorker(DeviceServiceCommandService commands, RuntimeConfig runtime,
                               @Value("${app.device-service-batch:5}") int batch) {
        if (batch < 1 || batch > 50) throw new IllegalStateException("Invalid device service batch size");
        this.commands = commands;
        this.runtime = runtime;
        this.batch = batch;
    }

    @Scheduled(fixedDelayString="${app.device-service-poll-ms:1000}",
        initialDelayString="${app.device-service-initial-delay-ms:10000}",
        scheduler="deviceServiceScheduler")
    public void tick() {
        if (!(runtime.roles().contains("all") || runtime.roles().contains("worker"))) return;
        for (int i = 0; i < batch; i++) {
            if (!commands.dispatchOne()) break;
        }
    }
}
