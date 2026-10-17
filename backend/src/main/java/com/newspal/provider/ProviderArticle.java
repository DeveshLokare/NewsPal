package com.newspal.provider;

import java.time.Instant;

/**
 * An article as returned by a {@link NewsProvider}, before it is stored.
 */
public record ProviderArticle(String url, String title, String summary, String imageUrl, String source,
		Instant publishedAt) {
}
