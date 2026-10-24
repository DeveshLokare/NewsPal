package com.newspal.provider.freenews;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.newspal.provider.NewsProvider;
import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderException;
import com.newspal.provider.ProviderRefPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * {@link NewsProvider} backed by Free News API (freenewsapi.io, SPEC-v2 §4). Its list
 * endpoint returns only ids and titles, so every article costs one extra
 * {@code /details} call.
 */
@Component
@EnableConfigurationProperties(FreeNewsProperties.class)
public class FreeNewsProvider implements NewsProvider {

	private static final Logger logger = LoggerFactory.getLogger(FreeNewsProvider.class);

	static final String REMAINING_HEADER = "X-RateLimit-Remaining-Day";

	static final String RESET_HEADER = "X-RateLimit-Reset-Day";

	/** Longest rate-limit wait we sit out before retrying once. */
	private static final long MAX_RETRY_WAIT_MS = 5_000;

	private final FreeNewsProperties properties;

	private final FreeNewsResponseParser parser;

	private final Clock clock;

	private final RestClient restClient;

	private final Object throttleLock = new Object();

	private long nextCallNanos = System.nanoTime();

	/** Set when the API rejects the key (401/403); only a restart clears it. */
	private volatile boolean keyRejected;

	/** Fetching is paused until this time after the daily quota ran out or ran low. */
	private volatile Instant pausedUntil;

	@Autowired
	public FreeNewsProvider(FreeNewsProperties properties, FreeNewsResponseParser parser, Clock clock) {
		this(properties, parser, clock, RestClient.builder().requestFactory(requestFactory(properties)));
	}

	FreeNewsProvider(FreeNewsProperties properties, FreeNewsResponseParser parser, Clock clock,
			RestClient.Builder builder) {
		this.properties = properties;
		this.parser = parser;
		this.clock = clock;
		builder.baseUrl(properties.baseUrl());
		if (properties.hasApiKey()) {
			builder.defaultHeader("x-api-key", properties.apiKey());
		}
		this.restClient = builder.build();
	}

	private static SimpleClientHttpRequestFactory requestFactory(FreeNewsProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.timeout());
		requestFactory.setReadTimeout(properties.timeout());
		return requestFactory;
	}

	@Override
	public boolean isEnabled() {
		Instant paused = this.pausedUntil;
		return this.properties.hasApiKey() && !this.keyRejected
				&& (paused == null || !this.clock.instant().isBefore(paused));
	}

	@Override
	public ProviderRefPage listLatest(String topic, Instant since, String cursor) {
		Map<String, Object> variables = listVariables(topic, cursor);
		if (since != null) {
			variables.put("published_after", isoSeconds(since));
		}
		return list(variables, topic);
	}

	@Override
	public ProviderRefPage listOlder(String topic, Instant before, Instant notBefore, String cursor) {
		Map<String, Object> variables = listVariables(topic, cursor);
		variables.put("published_before", isoSeconds(before));
		variables.put("published_after", isoSeconds(notBefore));
		return list(variables, topic);
	}

	@Override
	public Optional<ProviderArticle> details(String providerId) {
		String body = call((builder) -> builder.path("/details").queryParam("uuid", "{uuid}").build(providerId),
				"details");
		// 404: the provider dropped the article since it was listed.
		return (body != null) ? this.parser.parseDetails(body) : Optional.empty();
	}

	private Map<String, Object> listVariables(String topic, String cursor) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("topic", topic);
		variables.put("language", this.properties.language());
		if (this.properties.hasCountry()) {
			variables.put("country", this.properties.country());
		}
		if (cursor != null) {
			variables.put("cursor", cursor);
		}
		return variables;
	}

	private ProviderRefPage list(Map<String, Object> variables, String topic) {
		String body = call((builder) -> buildListUri(builder, variables), "news");
		if (body == null) {
			throw new ProviderException("Free News API returned 404 for news");
		}
		ProviderRefPage page = this.parser.parseList(body);
		logger.info("Free News API listed {} articles for topic {}", page.items().size(), topic);
		return page;
	}

	/**
	 * Builds a {@code /news} URI. Template variables are fully encoded, which matters
	 * for cursors (base64 with {@code =}) and timestamps ({@code :}).
	 */
	URI buildListUri(UriBuilder builder, Map<String, Object> variables) {
		builder.path("/news");
		for (String name : new String[] { "topic", "language", "country", "published_after", "published_before",
				"cursor" }) {
			if (variables.containsKey(name)) {
				builder.queryParam(name, "{" + name + "}");
			}
		}
		// Cursor pagination only: offset is capped at 10,000 and slower.
		builder.queryParam("order_by", "recent");
		return builder.build(variables);
	}

	/**
	 * Makes one call, retrying once after a short rate-limit wait.
	 * @return the body, or {@code null} for HTTP 404
	 */
	private String call(Function<UriBuilder, URI> uri, String what) {
		for (int attempt = 0;; attempt++) {
			throttle();
			try {
				ResponseEntity<String> response = this.restClient.get().uri(uri).retrieve().toEntity(String.class);
				recordRemaining(response.getHeaders());
				return response.getBody();
			}
			catch (RestClientResponseException ex) {
				int status = ex.getStatusCode().value();
				if (status == 404) {
					return null;
				}
				if (status == 401 || status == 403) {
					if (!this.keyRejected) {
						logger.error("Free News API rejected the API key (HTTP {}); fetching is off until restart",
								status);
					}
					this.keyRejected = true;
				}
				else if (status == 429) {
					FreeNewsResponseParser.Error error = this.parser.parseError(ex.getResponseBodyAsString());
					if (error.isQuotaExceeded()) {
						pause(ex.getResponseHeaders(), "quota exceeded");
					}
					else if (attempt == 0 && error.retryAfterMs() != null
							&& error.retryAfterMs() <= MAX_RETRY_WAIT_MS) {
						sleep(Duration.ofMillis(error.retryAfterMs()));
						continue;
					}
				}
				// The message is our own: never the URL or headers, which hold the key.
				throw new ProviderException("Free News API responded with HTTP " + status + " for " + what);
			}
			catch (RestClientException ex) {
				throw new ProviderException(
						"Free News API request failed for " + what + ": " + ex.getClass().getSimpleName());
			}
		}
	}

	/** Pauses fetching once the API reports that the daily quota is nearly used up. */
	void recordRemaining(HttpHeaders headers) {
		String remaining = headers.getFirst(REMAINING_HEADER);
		if (remaining == null) {
			return;
		}
		try {
			if (Integer.parseInt(remaining.trim()) < this.properties.minRemaining()) {
				pause(headers, remaining + " requests left today");
			}
		}
		catch (NumberFormatException ex) {
			// Unknown format: rely on our own quota count.
		}
	}

	private void pause(HttpHeaders headers, String reason) {
		Instant until = resetTime((headers != null) ? headers.getFirst(RESET_HEADER) : null);
		if (this.pausedUntil == null || this.pausedUntil.isBefore(until)) {
			logger.warn("Free News API {}; pausing news fetching until {}", reason, until);
			this.pausedUntil = until;
		}
	}

	/**
	 * When the daily quota resets. The docs don't give the header's format, so ISO
	 * timestamps, epoch seconds or millis, and seconds-until-reset are accepted; anything
	 * else (or a time that makes no sense) means the next UTC midnight.
	 */
	Instant resetTime(String header) {
		Instant now = this.clock.instant();
		Instant fallback = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC)
			.toInstant();
		Instant parsed = parseInstant(header, now);
		boolean plausible = parsed != null && parsed.isAfter(now) && parsed.isBefore(now.plus(2, ChronoUnit.DAYS));
		return plausible ? parsed : fallback;
	}

	private static Instant parseInstant(String value, Instant now) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		try {
			return OffsetDateTime.parse(trimmed).toInstant();
		}
		catch (DateTimeParseException ex) {
			// Not an ISO timestamp; try a number.
		}
		try {
			long number = Long.parseLong(trimmed);
			if (number > 100_000_000_000L) {
				return Instant.ofEpochMilli(number);
			}
			return (number > 1_000_000_000L) ? Instant.ofEpochSecond(number) : now.plusSeconds(number);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/** The API allows 2 calls per second, so calls from all threads are spaced out. */
	private void throttle() {
		synchronized (this.throttleLock) {
			long waitNanos = this.nextCallNanos - System.nanoTime();
			if (waitNanos > 0) {
				sleep(Duration.ofNanos(waitNanos));
			}
			this.nextCallNanos = System.nanoTime() + this.properties.minInterval().toNanos();
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ProviderException("Interrupted while waiting to call Free News API");
		}
	}

	private static String isoSeconds(Instant instant) {
		return instant.truncatedTo(ChronoUnit.SECONDS).toString();
	}

}
