package com.newspal.article;

import java.time.Instant;

import com.newspal.provider.ProviderArticle;
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
	void decodesHtmlEntities() {
		assertThat(ArticleCleaner.decodeEntities("Rai&rsquo;s party &amp; &#39;cake&#39; &#x2014; &bogus; & more"))
			.isEqualTo("Rai’s party & 'cake' — &bogus; & more");
		assertThat(ArticleCleaner.decodeEntities(null)).isNull();
	}

	@Test
	void cleansSummaryAndTitle() {
		ProviderArticle article = ArticleCleaner.clean(new ProviderArticle("id", "https://example.com/a",
				"Rock &amp; roll", "Table of Contents In our world of rising tech prices, is it worth it?", null,
				"Example", Instant.EPOCH), 500);
		assertThat(article.title()).isEqualTo("Rock & roll");
		assertThat(article.summary()).isEqualTo("In our world of rising tech prices, is it worth it?");
	}

	@Test
	void keepsTheArticleWhenTheSummaryIsOnlyClutter() {
		ProviderArticle article = ArticleCleaner.clean(new ProviderArticle("id", "https://example.com/a", "Title",
				"To use this website, cookies must be enabled in your browser.", null, "Example", Instant.EPOCH), 500);
		assertThat(article).isNotNull();
		assertThat(article.summary()).isNull();
	}

	@Test
	void dropsArticlesWithNoTextAtAll() {
		assertThat(ArticleCleaner.clean(new ProviderArticle("id", "https://example.com/v", "Video", " ", null,
				"Example", Instant.EPOCH), 500)).isNull();
	}

	@Test
	void collapseWhitespaceFlattensSnippets() {
		assertThat(ArticleCleaner.collapseWhitespace("  Line one\n\n  line\ttwo  ")).isEqualTo("Line one line two");
		assertThat(ArticleCleaner.collapseWhitespace(" \n ")).isNull();
	}

}
