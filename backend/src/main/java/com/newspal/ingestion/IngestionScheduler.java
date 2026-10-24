package com.newspal.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-process triggers for the background jobs (SPEC-v2 §5.2). On Render's free plan
 * these only fire while the service is awake; the external trigger
 * ({@link JobController}) wakes it every 2 hours, and the wake-up itself runs a pass
 * through {@link #onStartup()}.
 */
@Component
@EnableScheduling
@ConditionalOnBooleanProperty(name = "newspal.scheduling.enabled", matchIfMissing = true)
public class IngestionScheduler {

	private final JobRunner jobs;

	public IngestionScheduler(JobRunner jobs) {
		this.jobs = jobs;
	}

	/** Runs a pass right after startup, off the startup thread. */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		this.jobs.startInBackground();
	}

	/** Every 2 hours while awake; a no-op when the external trigger just ran one. */
	@Scheduled(cron = "0 5 */2 * * *", zone = "UTC")
	public void runPass() {
		this.jobs.runNow();
	}

	/** Covers a day in which the service stayed awake without any pass running. */
	@Scheduled(cron = "0 30 3 * * *", zone = "UTC")
	public void runDailyCleanup() {
		this.jobs.cleanUpIfDue();
	}

}
