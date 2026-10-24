package com.newspal.provider;

import java.time.Instant;

/**
 * An article as returned by a {@link NewsProvider}, before it is stored.
 * {@code providerId} is the provider's own id, or {@code null} if unknown.
 */
public record ProviderArticle(String providerId, String url, String title, String summary, String imageUrl,
		String source, Instant publishedAt) {
}
