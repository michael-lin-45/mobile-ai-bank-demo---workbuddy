package com.mobileagent;

import com.mobileagent.app.config.MemoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MemoryProperties.class)
public class MobileAiDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MobileAiDemoApplication.class, args);
    }
}
