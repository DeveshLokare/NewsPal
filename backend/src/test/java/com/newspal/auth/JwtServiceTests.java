package com.newspal.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.newspal.config.JwtProperties;
import com.newspal.config.SecurityConfig;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class JwtServiceTests {

	private static final String SECRET = "unit-test-secret-that-is-32-bytes-or-more";

	private final SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

	private final NimbusJwtDecoder decoder = createDecoder();

	@Test
	void issuedTokenCarriesUserIdAndExpiry() {
		Instant now = Instant.now();
		JwtService service = createService(Clock.fixed(now, ZoneOffset.UTC));
		JwtService.IssuedToken token = service.issue(42L);
		Jwt jwt = this.decoder.decode(token.value());
		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(SecurityConfig.ISSUER);
		assertThat(token.expiresAt()).isEqualTo(now.plus(Duration.ofDays(7)));
	}

	@Test
	void expiredTokenIsRejected() {
		Clock eightDaysAgo = Clock.fixed(Instant.now().minus(Duration.ofDays(8)), ZoneOffset.UTC);
		String token = createService(eightDaysAgo).issue(1L).value();
		assertThatExceptionOfType(JwtValidationException.class).isThrownBy(() -> this.decoder.decode(token));
	}

	private JwtService createService(Clock clock) {
		return new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(this.key)),
				new JwtProperties(SECRET, Duration.ofDays(7)), clock);
	}

	private NimbusJwtDecoder createDecoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(this.key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(SecurityConfig.ISSUER));
		return decoder;
	}

}
