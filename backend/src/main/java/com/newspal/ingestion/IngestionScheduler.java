package com.newspal.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background jobs. On Render's free tier these only run while the service is awake;
 * the feed's lazy refresh covers the rest (SPEC §4.3).
 */
@Component
@EnableScheduling
@ConditionalOnBooleanProperty(name = "newspal.scheduling.enabled", matchIfMissing = true)
public class IngestionScheduler {

	private static final Logger logger = LoggerFactory.getLogger(IngestionScheduler.class);

	private final IngestionService ingestion;

	private final CleanupService cleanup;

	private final TaskExecutor executor;

	public IngestionScheduler(IngestionService ingestion, CleanupService cleanup,
			@Qualifier("ingestionExecutor") TaskExecutor executor) {
		this.ingestion = ingestion;
		this.cleanup = cleanup;
		this.executor = executor;
	}

	/** Runs cleanup and a refresh pass right after startup, off the startup thread. */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		this.executor.execute(() -> {
			runCleanup();
			runRefresh();
		});
	}

	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
	public void runRefresh() {
		try {
			this.ingestion.refreshAllStaleFollowed();
		}
		catch (RuntimeException ex) {
			logger.warn("Scheduled refresh failed", ex);
		}
	}

	@Scheduled(cron = "0 15 * * * *", zone = "UTC")
	public void runCleanup() {
		try {
			this.cleanup.cleanUp();
		}
		catch (RuntimeException ex) {
			logger.warn("Scheduled cleanup failed", ex);
		}
	}

}
