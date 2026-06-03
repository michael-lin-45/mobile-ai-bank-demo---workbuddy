package com.mobileagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MobileAiDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MobileAiDemoApplication.class, args);
    }
}
