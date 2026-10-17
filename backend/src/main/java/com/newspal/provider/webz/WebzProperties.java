package com.newspal.provider.webz;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * webz.io settings ({@code newspal.webz.*}). A blank token disables fetching.
 */
@ConfigurationProperties("newspal.webz")
public record WebzProperties(String baseUrl, String endpoint, String token, Duration timeout) {

	public WebzProperties {
		baseUrl = (baseUrl != null) ? baseUrl : "https://api.webz.io";
		endpoint = (endpoint != null) ? endpoint : "/newsApiLite";
		timeout = (timeout != null) ? timeout : Duration.ofSeconds(10);
	}

	public boolean hasToken() {
		return this.token != null && !this.token.isBlank();
	}

}
