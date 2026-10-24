package com.newspal.provider.freenews;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Free News API settings ({@code newspal.freenews.*}, SPEC-v2 §4.8). A blank API key
 * disables fetching.
 * @param language language filter sent with every list call
 * @param country country filter, or blank for all countries
 * @param minInterval minimum spacing between calls (the API allows 2 per second)
 * @param minRemaining pause fetching until the daily reset once the API reports fewer
 * remaining requests than this
 */
@ConfigurationProperties("newspal.freenews")
public record FreeNewsProperties(String baseUrl, String apiKey, String language, String country, Duration timeout,
		Duration minInterval, Integer minRemaining) {

	public FreeNewsProperties {
		baseUrl = (baseUrl != null) ? baseUrl : "https://api.freenewsapi.io/v1";
		language = (language != null) ? language : "en";
		timeout = (timeout != null) ? timeout : Duration.ofSeconds(10);
		minInterval = (minInterval != null) ? minInterval : Duration.ofMillis(550);
		minRemaining = (minRemaining != null) ? minRemaining : 100;
	}

	public boolean hasApiKey() {
		return this.apiKey != null && !this.apiKey.isBlank();
	}

	public boolean hasCountry() {
		return this.country != null && !this.country.isBlank();
	}

}
