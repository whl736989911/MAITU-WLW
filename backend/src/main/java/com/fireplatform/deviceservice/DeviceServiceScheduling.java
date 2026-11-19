package com.fireplatform.deviceservice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Separate single-thread scheduler so command dispatch never competes with ingestion workers. */
@Configuration
public class DeviceServiceScheduling {
    @Bean("deviceServiceScheduler")
    public ThreadPoolTaskScheduler deviceServiceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("device-service-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
