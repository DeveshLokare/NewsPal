package com.newspal.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlUtilsTests {

	@Test
	void normalizeLowercasesHostAndDropsTrackingFragmentAndTrailingSlash() {
		assertThat(UrlUtils.normalize("HTTPS://WWW.Example.COM/News/Story/?utm_source=x&id=7&fbclid=abc#top"))
			.isEqualTo("https://www.example.com/News/Story?id=7");
	}

	@Test
	void normalizeKeepsPortAndNonTrackingParams() {
		assertThat(UrlUtils.normalize("http://example.com:8080/a?b=1&c=2")).isEqualTo("http://example.com:8080/a?b=1&c=2");
	}

	@Test
	void normalizeLeavesUnparseableInputTrimmed() {
		assertThat(UrlUtils.normalize("  not a url  ")).isEqualTo("not a url");
	}

	@Test
	void sameArticleWithTrackingParamsHashesTheSame() {
		String plain = UrlUtils.hash("https://example.com/story");
		assertThat(UrlUtils.hash("https://Example.com/story/?utm_campaign=feed")).isEqualTo(plain);
		assertThat(plain).hasSize(64).matches("[0-9a-f]+");
	}

	@Test
	void isHttpUrl() {
		assertThat(UrlUtils.isHttpUrl("https://example.com/x")).isTrue();
		assertThat(UrlUtils.isHttpUrl("javascript:alert(1)")).isFalse();
		assertThat(UrlUtils.isHttpUrl("ftp://example.com")).isFalse();
		assertThat(UrlUtils.isHttpUrl("not a url")).isFalse();
	}

}
