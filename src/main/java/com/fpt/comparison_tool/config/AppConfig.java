package com.fpt.comparison_tool.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Fixed thread pool for parallel test case execution.
     * Size matches the default parallelLimit in ExecutionConfig.
     */
    @Bean(name = "executionExecutor")
    public ExecutorService executionExecutor() {
        return Executors.newFixedThreadPool(20);
    }
}