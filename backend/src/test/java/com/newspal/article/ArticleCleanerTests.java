package com.newspal.article;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleCleanerTests {

	@Test
	void truncateLeavesShortTextAlone() {
		assertThat(ArticleCleaner.truncate("short", 10)).isEqualTo("short");
		assertThat(ArticleCleaner.truncate(null, 10)).isNull();
	}

	@Test
	void truncateCutsAtWordBoundaryWithinLimit() {
		String result = ArticleCleaner.truncate("the quick brown fox jumps over the lazy dog", 20);
		assertThat(result).isEqualTo("the quick brown fox…");
		assertThat(result.length()).isLessThanOrEqualTo(20);
	}

	@Test
	void truncateHardCutsWhenNoGoodBoundary() {
		String result = ArticleCleaner.truncate("abcdefghijklmnopqrstuvwxyz", 10);
		assertThat(result).isEqualTo("abcdefghi…").hasSize(10);
	}

	@Test
	void collapseWhitespaceFlattensSnippets() {
		assertThat(ArticleCleaner.collapseWhitespace("  Line one\n\n  line\ttwo  ")).isEqualTo("Line one line two");
		assertThat(ArticleCleaner.collapseWhitespace(" \n ")).isNull();
	}

}
