package com.newspal.feed.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response bodies for {@code /api/feed} and {@code /api/feed/older} (SPEC §7.4).
 */
public final class FeedDtos {

	private FeedDtos() {
	}

	/**
	 * A feed item. {@code id} is {@code null} for older-news items, which are not
	 * stored.
	 */
	public record ArticleDto(Long id, String url, String title, String summary, String imageUrl, String source,
			Instant publishedAt, String category, boolean archived) {
	}

	public record FeedPage(List<ArticleDto> items, String nextCursor,
			@JsonInclude(JsonInclude.Include.NON_NULL) Instant lastRefreshedAt) {
	}

}
