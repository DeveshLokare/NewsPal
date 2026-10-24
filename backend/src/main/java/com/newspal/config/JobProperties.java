package com.newspal.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background job settings ({@code newspal.jobs.*}, SPEC-v2 §5.2, §6).
 * @param token shared secret for {@code POST /api/internal/jobs/run}; blank disables
 * the endpoint
 * @param cleanupEvery minimum time between two cleanups (a little under a day, so a
 * daily trigger that fires a bit early still runs it)
 */
@ConfigurationProperties("newspal.jobs")
public record JobProperties(String token, @DefaultValue("PT23H") Duration cleanupEvery) {

	public boolean hasToken() {
		return this.token != null && !this.token.isBlank();
	}

}
