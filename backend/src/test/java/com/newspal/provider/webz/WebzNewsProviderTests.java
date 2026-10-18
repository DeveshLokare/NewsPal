package com.newspal.provider.webz;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.web.util.DefaultUriBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

class WebzNewsProviderTests {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));

	private final WebzNewsProvider provider = new WebzNewsProvider(
			new WebzProperties("https://api.webz.io", null, "abc-123", Duration.ofSeconds(5), null, null),
			new WebzResponseParser(JsonMapper.builder().build()), this.clock);

	@Test
	void buildsNewsApiRequestWithEncodedQuery() {
		URI uri = this.provider.buildUri(new DefaultUriBuilderFactory("https://api.webz.io").builder(),
				Map.of("token", "abc-123", "q", "category:\"Science and Technology\" language:english", "ts",
						1792071889130L));
		assertThat(uri.getPath()).isEqualTo("/api/news");
		assertThat(uri.getRawQuery()).isEqualTo("token=abc-123"
				+ "&q=category%3A%22Science%20and%20Technology%22%20language%3Aenglish"
				+ "&sort=crawled&format=json&size=10&includeSyndicated=false&ts=1792071889130");
	}

	@Test
	void olderNewsSortsByPublishedNewestFirst() {
		URI uri = this.provider.buildUri(new DefaultUriBuilderFactory("https://api.webz.io").builder(),
				Map.of("token", "t", "q", "x published:<100", "ts", 50L, "sort", "published", "order", "desc"));
		assertThat(uri.getRawQuery()).isEqualTo("token=t&q=x%20published%3A%3C100&sort=published&order=desc"
				+ "&format=json&size=10&includeSyndicated=false&ts=50");
	}

	@Test
	void omitsTsWhenNoSince() {
		URI uri = this.provider.buildUri(new DefaultUriBuilderFactory("https://api.webz.io").builder(),
				Map.of("token", "t", "q", "x"));
		assertThat(uri.getRawQuery()).doesNotContain("ts=");
	}

	@Test
	void lowBalancePausesFetchingUntilNextMonth() {
		this.provider.recordBalance(4.10);
		assertThat(this.provider.isEnabled()).isTrue();
		this.provider.recordBalance(0.25);
		assertThat(this.provider.isEnabled()).isFalse();
		this.clock.now = Instant.parse("2026-11-01T00:00:00Z");
		assertThat(this.provider.isEnabled()).as("credit refilled").isTrue();
	}

	@Test
	void unknownBalanceDoesNotPause() {
		this.provider.recordBalance(null);
		assertThat(this.provider.isEnabled()).isTrue();
	}

	private static final class MutableClock extends Clock {

		Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}

	}

}
