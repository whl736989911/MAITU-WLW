package com.fireplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication @EnableScheduling
public class FirePlatformApplication {
    public static void main(String[] args) { SpringApplication.run(FirePlatformApplication.class, args); }
}
