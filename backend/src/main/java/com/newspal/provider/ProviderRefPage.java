package com.newspal.provider;

import java.util.List;

/**
 * One page of article refs. {@code nextCursor} is provider-internal and must never be
 * sent to clients; {@code null} means there are no more pages.
 */
public record ProviderRefPage(List<ProviderRef> items, String nextCursor) {
}
