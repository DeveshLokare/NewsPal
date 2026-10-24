package com.newspal.ingestion;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Last-run times of once-a-day jobs, in {@code job_runs}.
 */
@Repository
public class JobRuns {

	private final JdbcClient jdbc;

	public JobRuns(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Atomically records a run of {@code name} at {@code now} if its last run was before
	 * {@code dueBefore} (or it never ran).
	 * @return {@code true} if this caller should run the job
	 */
	public boolean claim(String name, Instant now, Instant dueBefore) {
		return !this.jdbc.sql("""
				INSERT INTO job_runs (name, last_run_at) VALUES (:name, :now)
				ON CONFLICT (name) DO UPDATE SET last_run_at = :now
				WHERE job_runs.last_run_at < :dueBefore
				RETURNING name""")
			.param("name", name)
			.param("now", Timestamp.from(now))
			.param("dueBefore", Timestamp.from(dueBefore))
			.query(String.class)
			.list()
			.isEmpty();
	}

}
