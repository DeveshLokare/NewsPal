package com.newspal.provider;

import java.util.List;

/**
 * One page of live results. {@code nextCursor} is provider-internal (it may contain
 * credentials) and must never be sent to clients.
 */
public record ProviderPage(List<ProviderArticle> items, String nextCursor) {
}
