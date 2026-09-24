package com.tameem.pricewatch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
@Configuration
public class ScraperExecutorConfig {
    @Bean(name = "scraperExecutor")
    public ExecutorService scraperExecutor() {
        return Executors.newFixedThreadPool(6);
    }
}
