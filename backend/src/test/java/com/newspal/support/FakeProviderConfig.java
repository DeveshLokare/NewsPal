package com.newspal.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the webz.io provider with a {@link FakeNewsProvider}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeProviderConfig {

	@Bean
	@Primary
	FakeNewsProvider fakeNewsProvider() {
		return new FakeNewsProvider();
	}

}
