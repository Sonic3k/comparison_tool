package com.fpt.comparison_tool.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    // RestTemplate bean lives in RestTemplateConfig (trust-all SSL + pooling).

    @Bean(name = "executionExecutor")
    public ExecutorService executionExecutor() {
        return Executors.newFixedThreadPool(20);
    }
}
