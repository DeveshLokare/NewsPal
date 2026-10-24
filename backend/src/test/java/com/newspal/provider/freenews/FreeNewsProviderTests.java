package com.newspal.provider.freenews;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.newspal.provider.ProviderException;
import com.newspal.provider.ProviderRefPage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FreeNewsProviderTests {

	private static final String BASE = "https://api.test/v1";

	private static final String EMPTY_LIST = "{\"data\": [], \"meta\": {\"has_more\": false}}";

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-24T12:00:00Z"));

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(this.builder).build();

	private final FreeNewsProvider provider = provider("secret-key", null);

	@Test
	void listsLatestWithKeyHeaderAndEncodedParameters() {
		this.server
			.expect(requestTo(BASE + "/news?topic=technology&language=en&published_after=2026-10-24T10%3A00%3A00Z"
					+ "&cursor=eyJ2IjoxfQ%3D%3D&order_by=recent"))
			.andExpect(header("x-api-key", "secret-key"))
			.andRespond(withSuccess("""
					{"data": [{"uuid": "u1", "title": "T", "published_at": "2026-10-24T11:00:00Z"}],
					 "meta": {"has_more": true, "next_cursor": "next"}}""", MediaType.APPLICATION_JSON));
		ProviderRefPage page = this.provider.listLatest("technology", Instant.parse("2026-10-24T10:00:00.123Z"),
				"eyJ2IjoxfQ==");
		assertThat(page.items()).hasSize(1);
		assertThat(page.nextCursor()).isEqualTo("next");
		this.server.verify();
	}

	@Test
	void listsOlderWithinTheWindowAndCountryWhenSet() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		FreeNewsProvider provider = new FreeNewsProvider(properties("k", "IN"),
				new FreeNewsResponseParser(JsonMapper.builder().build()), this.clock, builder);
		server
			.expect(requestTo(BASE + "/news?topic=world&language=en&country=IN"
					+ "&published_after=2026-09-24T00%3A00%3A00Z&published_before=2026-10-17T00%3A00%3A00Z"
					+ "&order_by=recent"))
			.andRespond(withSuccess(EMPTY_LIST, MediaType.APPLICATION_JSON));
		provider.listOlder("world", Instant.parse("2026-10-17T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"),
				null);
		server.verify();
	}

	@Test
	void missingDetailsAreEmpty() {
		this.server.expect(requestTo(BASE + "/details?uuid=gone"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"error\": \"Not found\"}"));
		assertThat(this.provider.details("gone")).isEmpty();
	}

	@Test
	void retriesOnceAfterAShortRateLimit() {
		this.server.expect(requestTo(BASE + "/details?uuid=u1"))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
				.body("{\"error\": \"Too Many Requests\", \"retry_after_ms\": 5}"));
		this.server.expect(requestTo(BASE + "/details?uuid=u1"))
			.andRespond(withSuccess("""
					{"data": {"uuid": "u1", "title": "T", "published_at": "2026-10-24T11:00:00Z",
					          "original_url": "https://example.com/a", "incipit": "Intro"}}""",
					MediaType.APPLICATION_JSON));
		assertThat(this.provider.details("u1")).isPresent();
		this.server.verify();
	}

	@Test
	void secondRateLimitInARowFails() {
		for (int i = 0; i < 2; i++) {
			this.server.expect(requestTo(BASE + "/details?uuid=u1"))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
					.body("{\"error\": \"Too Many Requests\", \"retry_after_ms\": 5}"));
		}
		assertThatExceptionOfType(ProviderException.class).isThrownBy(() -> this.provider.details("u1"));
		assertThat(this.provider.isEnabled()).as("a burst limit doesn't pause fetching").isTrue();
	}

	@Test
	void dailyQuotaExceededPausesUntilTheReset() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(FreeNewsProvider.RESET_HEADER, "2026-10-25T00:00:00Z");
		this.server.expect(requestTo(BASE + "/details?uuid=u1"))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers)
				.body("{\"error\": \"Daily quota exceeded\"}"));
		assertThatExceptionOfType(ProviderException.class).isThrownBy(() -> this.provider.details("u1"))
			.withMessageNotContaining("secret-key");
		assertThat(this.provider.isEnabled()).isFalse();
		this.clock.now = Instant.parse("2026-10-25T00:00:01Z");
		assertThat(this.provider.isEnabled()).isTrue();
	}

	@Test
	void lowRemainingQuotaPausesFetching() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(FreeNewsProvider.REMAINING_HEADER, "99");
		this.server.expect(requestTo(BASE + "/news?topic=health&language=en&order_by=recent"))
			.andRespond(withSuccess(EMPTY_LIST, MediaType.APPLICATION_JSON).headers(headers));
		this.provider.listLatest("health", null, null);
		assertThat(this.provider.isEnabled()).isFalse();
		this.clock.now = Instant.parse("2026-10-25T00:00:00Z");
		assertThat(this.provider.isEnabled()).as("next UTC midnight without a reset header").isTrue();
	}

	@Test
	void plentyOfRemainingQuotaKeepsFetching() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(FreeNewsProvider.REMAINING_HEADER, "4321");
		this.server.expect(requestTo(BASE + "/news?topic=health&language=en&order_by=recent"))
			.andRespond(withSuccess(EMPTY_LIST, MediaType.APPLICATION_JSON).headers(headers));
		this.provider.listLatest("health", null, null);
		assertThat(this.provider.isEnabled()).isTrue();
	}

	@Test
	void rejectedKeyDisablesFetching() {
		this.server.expect(requestTo(BASE + "/news?topic=health&language=en&order_by=recent"))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\": \"Invalid API key\"}"));
		assertThatExceptionOfType(ProviderException.class)
			.isThrownBy(() -> this.provider.listLatest("health", null, null));
		assertThat(this.provider.isEnabled()).isFalse();
		this.clock.now = this.clock.now.plus(Duration.ofDays(2));
		assertThat(this.provider.isEnabled()).as("only a restart clears it").isFalse();
	}

	@Test
	void blankKeyMeansDisabled() {
		assertThat(provider("", null).isEnabled()).isFalse();
		assertThat(provider(null, null).isEnabled()).isFalse();
	}

	@Test
	void resetTimeAcceptsCommonFormats() {
		Instant midnight = Instant.parse("2026-10-25T00:00:00Z");
		assertThat(this.provider.resetTime("2026-10-24T18:00:00Z")).isEqualTo(Instant.parse("2026-10-24T18:00:00Z"));
		assertThat(this.provider.resetTime("2026-10-24T20:00:00+02:00"))
			.isEqualTo(Instant.parse("2026-10-24T18:00:00Z"));
		assertThat(this.provider.resetTime(String.valueOf(midnight.getEpochSecond()))).isEqualTo(midnight);
		assertThat(this.provider.resetTime(String.valueOf(midnight.toEpochMilli()))).isEqualTo(midnight);
		assertThat(this.provider.resetTime("3600")).isEqualTo(Instant.parse("2026-10-24T13:00:00Z"));
		assertThat(this.provider.resetTime(null)).isEqualTo(midnight);
		assertThat(this.provider.resetTime("tomorrow-ish")).isEqualTo(midnight);
		assertThat(this.provider.resetTime("2026-10-24T11:00:00Z")).as("in the past").isEqualTo(midnight);
	}

	private FreeNewsProvider provider(String apiKey, String country) {
		return new FreeNewsProvider(properties(apiKey, country), new FreeNewsResponseParser(JsonMapper.builder().build()),
				this.clock, this.builder);
	}

	private static FreeNewsProperties properties(String apiKey, String country) {
		return new FreeNewsProperties(BASE, apiKey, null, country, Duration.ofSeconds(5), Duration.ZERO, null);
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
