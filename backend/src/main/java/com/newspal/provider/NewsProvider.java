package com.newspal.provider;

import java.time.Instant;
import java.util.Optional;

/**
 * Source of news articles (SPEC-v2 §4.4). Each method is exactly one provider call,
 * so callers can count every call against the quota. Implementations must not retry
 * on their own, except briefly for a rate-limit response; the next refresh acts as
 * the retry.
 */
public interface NewsProvider {

	/**
	 * Whether the provider can be called: an API key is configured and the provider
	 * hasn't paused itself (quota used up, key rejected). When {@code false}, callers
	 * skip it without spending quota.
	 */
	boolean isEnabled();

	/**
	 * One page of the newest article refs for {@code topic}, newest first.
	 * @param since only articles published at or after this, or {@code null} for no
	 * lower bound
	 * @param cursor {@code null} for the first page, else a previous page's
	 * {@link ProviderRefPage#nextCursor()}
	 */
	ProviderRefPage listLatest(String topic, Instant since, String cursor);

	/**
	 * One page of article refs published before {@code before} and not earlier than
	 * {@code notBefore}, newest first (SPEC §5).
	 */
	ProviderRefPage listOlder(String topic, Instant before, Instant notBefore, String cursor);

	/**
	 * The full article for a ref, or empty if the provider no longer has it or it has
	 * no usable URL.
	 */
	Optional<ProviderArticle> details(String providerId);

}
