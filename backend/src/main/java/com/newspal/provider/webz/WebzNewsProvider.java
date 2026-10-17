package com.newspal.provider.webz;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.newspal.provider.NewsProvider;
import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderException;
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
 * {@link NewsProvider} backed by the webz.io News API Lite.
 */
@Component
@EnableConfigurationProperties(WebzProperties.class)
public class WebzNewsProvider implements NewsProvider {

	private static final Logger logger = LoggerFactory.getLogger(WebzNewsProvider.class);

	private final WebzProperties properties;

	private final WebzResponseParser parser;

	private final RestClient restClient;

	public WebzNewsProvider(WebzProperties properties, WebzResponseParser parser) {
		this.properties = properties;
		this.parser = parser;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.timeout());
		requestFactory.setReadTimeout(properties.timeout());
		this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
	}

	@Override
	public boolean isEnabled() {
		return this.properties.hasToken();
	}

	@Override
	public List<ProviderArticle> fetchLatest(String query, Instant since) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("token", this.properties.token());
		variables.put("q", query);
		if (since != null) {
			variables.put("ts", since.toEpochMilli());
		}
		String body;
		try {
			body = this.restClient.get().uri((builder) -> buildUri(builder, variables)).retrieve().body(String.class);
		}
		catch (RestClientResponseException ex) {
			// Never propagate the original message: it contains the request URL and token.
			throw new ProviderException("webz.io responded with HTTP " + ex.getStatusCode().value());
		}
		catch (RestClientException ex) {
			throw new ProviderException("webz.io request failed: " + ex.getClass().getSimpleName());
		}
		WebzResponseParser.Result result = this.parser.parse(body);
		logger.info("webz.io returned {} articles for [{}]; requests left: {}", result.articles().size(), query,
				result.requestsLeft());
		return result.articles();
	}

	private URI buildUri(UriBuilder builder, Map<String, Object> variables) {
		// Template variables are fully encoded, which matters for quotes and colons in q.
		builder.path(this.properties.endpoint()).queryParam("token", "{token}").queryParam("q", "{q}");
		if (variables.containsKey("ts")) {
			builder.queryParam("ts", "{ts}");
		}
		return builder.build(variables);
	}

}
