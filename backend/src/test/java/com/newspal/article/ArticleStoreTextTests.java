package com.newspal.article;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleStoreTextTests {

	@Test
	void truncateLeavesShortTextAlone() {
		assertThat(ArticleStore.truncate("short", 10)).isEqualTo("short");
		assertThat(ArticleStore.truncate(null, 10)).isNull();
	}

	@Test
	void truncateCutsAtWordBoundaryWithinLimit() {
		String result = ArticleStore.truncate("the quick brown fox jumps over the lazy dog", 20);
		assertThat(result).isEqualTo("the quick brown fox…");
		assertThat(result.length()).isLessThanOrEqualTo(20);
	}

	@Test
	void truncateHardCutsWhenNoGoodBoundary() {
		String result = ArticleStore.truncate("abcdefghijklmnopqrstuvwxyz", 10);
		assertThat(result).isEqualTo("abcdefghi…").hasSize(10);
	}

	@Test
	void collapseWhitespaceFlattensSnippets() {
		assertThat(ArticleStore.collapseWhitespace("  Line one\n\n  line\ttwo  ")).isEqualTo("Line one line two");
		assertThat(ArticleStore.collapseWhitespace(" \n ")).isNull();
	}

}
