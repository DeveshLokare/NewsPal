package com.newspal.ingestion;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

import com.newspal.config.NewsProperties;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Monthly webz.io call budget, stored in {@code api_usage} so it survives restarts
 * (SPEC §4.2, §6.2).
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
	 * Counts one call against {@code bucket} if this month's limit allows it.
	 * @return {@code true} if the call may be made
	 */
	public boolean tryAcquire(Bucket bucket) {
		int limit = limit(bucket);
		if (limit <= 0) {
			return false;
		}
		return !this.jdbc.sql("""
				INSERT INTO api_usage (month, bucket, calls) VALUES (:month, :bucket, 1)
				ON CONFLICT (month, bucket) DO UPDATE SET calls = api_usage.calls + 1
				WHERE api_usage.calls < :limit
				RETURNING calls""")
			.param("month", currentMonth())
			.param("bucket", bucket.column)
			.param("limit", limit)
			.query(Integer.class)
			.list()
			.isEmpty();
	}

	public int used(Bucket bucket) {
		return this.jdbc.sql("SELECT calls FROM api_usage WHERE month = ? AND bucket = ?")
			.params(currentMonth(), bucket.column)
			.query(Integer.class)
			.optional()
			.orElse(0);
	}

	private int limit(Bucket bucket) {
		return switch (bucket) {
			case INGESTION -> this.properties.quota().ingestionMonthly();
			case OLDER -> this.properties.quota().olderMonthly();
		};
	}

	String currentMonth() {
		return YearMonth.from(this.clock.instant().atZone(ZoneOffset.UTC)).toString();
	}

}
