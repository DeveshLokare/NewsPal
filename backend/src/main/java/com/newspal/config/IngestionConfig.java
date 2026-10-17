package com.newspal.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static final Logger logger = LoggerFactory.getLogger(IngestionConfig.class);

	@Bean
	ThreadPoolTaskExecutor ingestionExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(20);
		executor.setThreadNamePrefix("ingestion-");
		// A full queue only means refreshes are already pending; never fail a request for it.
		executor.setRejectedExecutionHandler((task, pool) -> logger.warn("Ingestion queue full; skipping a refresh"));
		executor.setWaitForTasksToCompleteOnShutdown(false);
		return executor;
	}

}
