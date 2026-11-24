package com.paperflow.main;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.paperflow")
@MapperScan("com.paperflow.adapters.persistence")
@EnableConfigurationProperties({WorkspaceProperties.class, CausalProperties.class})
@EnableScheduling
public class Paperflow {
    public static void main(String[] args) {
        SpringApplication.run(Paperflow.class, args);
    }
}
