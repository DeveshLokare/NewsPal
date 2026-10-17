package com.newspal.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * News ingestion, retention and quota settings ({@code newspal.news.*}, SPEC §4.5).
 */
@ConfigurationProperties("newspal.news")
public record NewsProperties(@DefaultValue("PT8H") Duration refreshTtl, @DefaultValue("P3D") Duration retention,
		@DefaultValue("200") int maxPerCategory, @DefaultValue("500") int summaryMaxChars,
		@DefaultValue Quota quota) {

	public record Quota(@DefaultValue("720") int ingestionMonthly, @DefaultValue("250") int olderMonthly) {
	}

}
