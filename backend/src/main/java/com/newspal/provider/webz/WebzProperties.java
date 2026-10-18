package com.newspal.provider.webz;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * webz.io settings ({@code newspal.webz.*}). A blank token disables fetching.
 */
@ConfigurationProperties("newspal.webz")
public record WebzProperties(String baseUrl, String endpoint, String token, Duration timeout, Duration minInterval,
		Double minBalance) {

	public WebzProperties {
		baseUrl = (baseUrl != null) ? baseUrl : "https://api.webz.io";
		endpoint = (endpoint != null) ? endpoint : "/api/news";
		timeout = (timeout != null) ? timeout : Duration.ofSeconds(10);
		minInterval = (minInterval != null) ? minInterval : Duration.ofSeconds(2);
		minBalance = (minBalance != null) ? minBalance : 0.30;
	}

	public boolean hasToken() {
		return this.token != null && !this.token.isBlank();
	}

}
