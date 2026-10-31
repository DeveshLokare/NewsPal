package com.newspal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor for background news refreshes (SPEC §8.4).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NewsProperties.class)
public class IngestionConfig {

	@Bean
	ThreadPoolTaskExecutor ingestionExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(20);
		executor.setThreadNamePrefix("ingestion-");
		executor.setWaitForTasksToCompleteOnShutdown(false);
		return executor;
	}

}
