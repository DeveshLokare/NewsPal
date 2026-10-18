package com.newspal.provider.webz;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.newspal.provider.NewsProvider;
import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderException;
import com.newspal.provider.ProviderPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * {@link NewsProvider} backed by the webz.io News API ({@code /api/news}).
 */
@Component
@EnableConfigurationProperties(WebzProperties.class)
public class WebzNewsProvider implements NewsProvider {

	private static final Logger logger = LoggerFactory.getLogger(WebzNewsProvider.class);

	/** Posts per call; webz.io bills per post, so this mostly sets refresh granularity. */
	private static final int PAGE_SIZE = 10;

	private final WebzProperties properties;

	private final WebzResponseParser parser;

	private final RestClient restClient;

	private final Object throttleLock = new Object();

	private long nextCallNanos = System.nanoTime();

	private final Clock clock;

	/** Month in which webz.io last reported a balance below {@code min-balance}, if any. */
	private volatile YearMonth lowBalanceMonth;

	public WebzNewsProvider(WebzProperties properties, WebzResponseParser parser, Clock clock) {
		this.properties = properties;
		this.parser = parser;
		this.clock = clock;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.timeout());
		requestFactory.setReadTimeout(properties.timeout());
		this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
	}

	@Override
	public boolean isEnabled() {
		// The credit refills monthly, so a low balance only pauses fetching until next month.
		return this.properties.hasToken() && !currentMonth().equals(this.lowBalanceMonth);
	}

	/** Records the balance webz.io reported; pauses fetching when it drops below the floor. */
	void recordBalance(Double balance) {
		if (balance != null && balance < this.properties.minBalance()) {
			if (!currentMonth().equals(this.lowBalanceMonth)) {
				logger.warn("webz.io balance {} is below {}; pausing news fetching until next month", balance,
						this.properties.minBalance());
			}
			this.lowBalanceMonth = currentMonth();
		}
	}

	private YearMonth currentMonth() {
		return YearMonth.from(this.clock.instant().atZone(ZoneOffset.UTC));
	}

	@Override
	public List<ProviderArticle> fetchLatest(String query, Instant since) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("token", this.properties.token());
		variables.put("q", query);
		if (since != null) {
			variables.put("ts", since.toEpochMilli());
		}
		WebzResponseParser.Result result = call((builder) -> buildUri(builder, variables), query);
		return result.articles();
	}

	@Override
	public ProviderPage fetchOlder(String query, Instant before, Instant notBefore, String cursor) {
		WebzResponseParser.Result result;
		if (cursor != null) {
			// webz.io's own "next" path: already encoded and carrying the token.
			if (!cursor.startsWith(this.properties.endpoint() + "?")) {
				throw new ProviderException("Unexpected webz.io next path");
			}
			URI next = URI.create(this.properties.baseUrl() + cursor);
			result = call((builder) -> next, query);
		}
		else {
			Map<String, Object> variables = new HashMap<>();
			variables.put("token", this.properties.token());
			variables.put("q", query + " published:<" + before.toEpochMilli());
			// The default search window is 3 days; ts widens it back to notBefore.
			variables.put("ts", notBefore.toEpochMilli());
			result = call((builder) -> buildUri(builder, variables), query);
		}
		return new ProviderPage(result.articles(), result.hasMore() ? result.next() : null);
	}

	private WebzResponseParser.Result call(Function<UriBuilder, URI> uri, String query) {
		String body;
		try {
			throttle();
			body = this.restClient.get().uri(uri).retrieve().body(String.class);
		}
		catch (RestClientResponseException ex) {
			// Never propagate the original message: it contains the request URL and token.
			throw new ProviderException("webz.io responded with HTTP " + ex.getStatusCode().value());
		}
		catch (RestClientException ex) {
			throw new ProviderException("webz.io request failed: " + ex.getClass().getSimpleName());
		}
		WebzResponseParser.Result result = this.parser.parse(body);
		logger.info("webz.io returned {} articles for [{}]; balance: {}", result.articles().size(), query,
				result.remaining());
		recordBalance(result.remaining());
		return result;
	}

	/**
	 * webz.io answers HTTP 429 to calls made in quick succession, so calls from all
	 * threads are spaced at least {@code min-interval} apart. This can add up to that
	 * interval to an older-news request; refreshes run in the background.
	 */
	private void throttle() {
		synchronized (this.throttleLock) {
			long waitNanos = this.nextCallNanos - System.nanoTime();
			if (waitNanos > 0) {
				try {
					Thread.sleep(Duration.ofNanos(waitNanos));
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new ProviderException("Interrupted while waiting to call webz.io");
				}
			}
			this.nextCallNanos = System.nanoTime() + this.properties.minInterval().toNanos();
		}
	}

	URI buildUri(UriBuilder builder, Map<String, Object> variables) {
		// Template variables are fully encoded, which matters for quotes and colons in q.
		builder.path(this.properties.endpoint())
			.queryParam("token", "{token}")
			.queryParam("q", "{q}")
			.queryParam("sort", "crawled")
			.queryParam("format", "json")
			.queryParam("size", PAGE_SIZE)
			// Skip syndicated copies of the same story from other sites.
			.queryParam("includeSyndicated", "false");
		if (variables.containsKey("ts")) {
			// Only posts crawled after this time (epoch millis).
			builder.queryParam("ts", "{ts}");
		}
		return builder.build(variables);
	}

}
