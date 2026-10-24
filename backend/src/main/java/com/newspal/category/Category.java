package com.newspal.category;

import java.time.Instant;

/**
 * A news category row (SPEC §3). {@code providerQuery} is the Free News API
 * topic (SPEC-v2 §4.3).
 */
public record Category(short id, String slug, String name, String providerQuery, short sortOrder,
		Instant lastFetchedAt) {
}
