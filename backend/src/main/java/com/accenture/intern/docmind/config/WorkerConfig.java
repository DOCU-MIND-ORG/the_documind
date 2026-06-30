package com.accenture.intern.docmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class WorkerConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService workerExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
