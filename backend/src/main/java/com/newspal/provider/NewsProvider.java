package com.newspal.provider;

import java.time.Instant;
import java.util.List;

/**
 * Source of news articles (SPEC §8.2). Implementations must not retry on their own;
 * the next lazy refresh acts as the retry.
 */
public interface NewsProvider {

	/**
	 * Whether the provider is configured (for example, an API token is present). When
	 * {@code false}, ingestion is skipped without spending quota.
	 */
	boolean isEnabled();

	/**
	 * Fetch the newest articles matching {@code query}.
	 * @param query the provider-specific query stored on the category
	 * @param since only articles newer than this, or {@code null} for the provider's
	 * default window
	 */
	List<ProviderArticle> fetchLatest(String query, Instant since);

	/**
	 * Fetch one page of articles published before {@code before} and not earlier than
	 * {@code notBefore} (SPEC §5).
	 * @param cursor {@code null} for the first page, else a previous page's
	 * {@link ProviderPage#nextCursor()}
	 */
	ProviderPage fetchOlder(String query, Instant before, Instant notBefore, String cursor);

}
