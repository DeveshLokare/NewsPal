package com.newspal.category;

import java.time.Instant;

/**
 * A news category row (SPEC §3). {@code providerQuery} is the webz.io {@code q} value.
 */
public record Category(short id, String slug, String name, String providerQuery, short sortOrder,
		Instant lastFetchedAt) {
}
