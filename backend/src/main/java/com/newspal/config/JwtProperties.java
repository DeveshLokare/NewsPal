package com.newspal.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings ({@code newspal.jwt.*}). The application refuses to start without a
 * secret of at least 32 bytes.
 */
@ConfigurationProperties("newspal.jwt")
public record JwtProperties(String secret, Duration ttl) {

	static final int MIN_SECRET_BYTES = 32;

	public JwtProperties {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"newspal.jwt.secret (env JWT_SECRET) must be set to at least " + MIN_SECRET_BYTES + " bytes");
		}
		if (ttl == null) {
			ttl = Duration.ofDays(7);
		}
	}

}
