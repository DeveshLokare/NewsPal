package com.newspal.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * News ingestion, retention and quota settings ({@code newspal.news.*}, SPEC §4.5).
 */
@ConfigurationProperties("newspal.news")
public record NewsProperties(@DefaultValue("PT84H") Duration refreshTtl, @DefaultValue("P7D") Duration retention,
		@DefaultValue("200") int maxPerCategory, @DefaultValue("500") int summaryMaxChars,
		@DefaultValue Quota quota, @DefaultValue Older older) {

	public record Quota(@DefaultValue("68") int ingestionMonthly, @DefaultValue("8") int olderMonthly) {
	}

	/**
	 * Live "older news" lookups (SPEC §5).
	 * @param cacheTtl how long a fetched page (and its cursor) is reused
	 * @param perUserDaily cache-missing lookups each user may make per UTC day
	 * @param maxAge how far back older news may go
	 */
	public record Older(@DefaultValue("PT1H") Duration cacheTtl, @DefaultValue("10") int perUserDaily,
			@DefaultValue("P30D") Duration maxAge) {
	}

}
