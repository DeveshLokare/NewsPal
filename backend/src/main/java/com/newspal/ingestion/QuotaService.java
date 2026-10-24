package com.newspal.ingestion;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.newspal.config.NewsProperties;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Daily news-provider call budget, stored in {@code api_usage} so it survives restarts
 * (SPEC-v2 §4.5). Every provider call counts, list or details.
 */
@Service
public class QuotaService {

	public enum Bucket {

		INGESTION("ingestion"), OLDER("older");

		private final String column;

		Bucket(String column) {
			this.column = column;
		}

	}

	private final JdbcClient jdbc;

	private final NewsProperties properties;

	private final Clock clock;

	public QuotaService(JdbcClient jdbc, NewsProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Counts one call against {@code bucket} if today's limit allows it.
	 * @return {@code true} if the call may be made
	 */
	public boolean tryAcquire(Bucket bucket) {
		return tryAcquire(bucket, 1);
	}

	/**
	 * Counts {@code calls} calls against {@code bucket}, all or none, if today's limit
	 * allows them.
	 * @return {@code true} if the calls may be made
	 */
	public boolean tryAcquire(Bucket bucket, int calls) {
		int limit = limit(bucket);
		if (calls <= 0) {
			return true;
		}
		if (calls > limit) {
			return false;
		}
		return !this.jdbc.sql("""
				INSERT INTO api_usage (period, bucket, calls) VALUES (:period, :bucket, :calls)
				ON CONFLICT (period, bucket) DO UPDATE SET calls = api_usage.calls + :calls
				WHERE api_usage.calls + :calls <= :limit
				RETURNING calls""")
			.param("period", currentPeriod())
			.param("bucket", bucket.column)
			.param("calls", calls)
			.param("limit", limit)
			.query(Integer.class)
			.list()
			.isEmpty();
	}

	public int used(Bucket bucket) {
		return this.jdbc.sql("SELECT calls FROM api_usage WHERE period = ? AND bucket = ?")
			.params(currentPeriod(), bucket.column)
			.query(Integer.class)
			.optional()
			.orElse(0);
	}

	private int limit(Bucket bucket) {
		return switch (bucket) {
			case INGESTION -> this.properties.quota().ingestionDaily();
			case OLDER -> this.properties.quota().olderDaily();
		};
	}

	/** Today's {@code api_usage.period}, e.g. {@code 2026-10-24} (UTC). */
	String currentPeriod() {
		return LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC).toString();
	}

}
