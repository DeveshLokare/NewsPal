package com.newspal.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JwtPropertiesTests {

	@Test
	void rejectsMissingSecret() {
		assertThatIllegalStateException().isThrownBy(() -> new JwtProperties(null, Duration.ofDays(1)));
	}

	@Test
	void rejectsShortSecret() {
		assertThatIllegalStateException().isThrownBy(() -> new JwtProperties("too-short", Duration.ofDays(1)));
	}

	@Test
	void defaultsTtlToSevenDays() {
		JwtProperties properties = new JwtProperties("x".repeat(32), null);
		assertThat(properties.ttl()).isEqualTo(Duration.ofDays(7));
	}

}
