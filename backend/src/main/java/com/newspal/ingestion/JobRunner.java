package com.newspal.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import com.newspal.config.JobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * One pass of the background jobs (SPEC-v2 §5, §6): the daily cleanup if it's due,
 * then a refresh of every stale category. Startup, the in-process schedule and the
 * external trigger all go through here, and only one pass runs at a time. Overlaps
 * with the feed's lazy refresh are harmless, since each category is claimed in the
 * database before it is fetched.
 */
@Service
@EnableConfigurationProperties(JobProperties.class)
public class JobRunner {

	static final String CLEANUP = "cleanup";

	private static final Logger logger = LoggerFactory.getLogger(JobRunner.class);

	private final IngestionService ingestion;

	private final CleanupService cleanup;

	private final JobRuns jobRuns;

	private final JobProperties properties;

	private final Clock clock;

	private final AtomicBoolean running = new AtomicBoolean();

	public JobRunner(IngestionService ingestion, CleanupService cleanup, JobRuns jobRuns, JobProperties properties,
			Clock clock) {
		this.ingestion = ingestion;
		this.cleanup = cleanup;
		this.jobRuns = jobRuns;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Starts a pass in the background and returns at once.
	 * @return {@code false} if a pass is already running
	 */
	public boolean startInBackground() {
		if (!this.running.compareAndSet(false, true)) {
			return false;
		}
		// Its own thread, not the ingestion pool, so a full pool can't drop the pass
		// and leave it marked as running.
		Thread.ofVirtual().name("jobs").start(this::runAndRelease);
		return true;
	}

	/**
	 * Runs a pass on the calling thread.
	 * @return {@code false} if a pass was already running, so none was started
	 */
	public boolean runNow() {
		if (!this.running.compareAndSet(false, true)) {
			return false;
		}
		runAndRelease();
		return true;
	}

	public boolean isRunning() {
		return this.running.get();
	}

	/** Runs the cleanup if it hasn't run within {@code cleanup-every}. */
	public void cleanUpIfDue() {
		Instant now = this.clock.instant();
		if (!this.jobRuns.claim(CLEANUP, now, now.minus(this.properties.cleanupEvery()))) {
			return;
		}
		try {
			this.cleanup.cleanUp();
		}
		catch (RuntimeException ex) {
			// Not retried until the next day: a failed cleanup only leaves old rows a bit longer.
			logger.warn("Cleanup failed", ex);
		}
	}

	private void runAndRelease() {
		try {
			cleanUpIfDue();
			int inserted = this.ingestion.refreshAllStale();
			logger.info("Job pass finished: {} new articles", inserted);
		}
		catch (RuntimeException ex) {
			logger.warn("Job pass failed", ex);
		}
		finally {
			this.running.set(false);
		}
	}

}
