package com.newspal.provider;

import java.time.Instant;

/**
 * A lightweight article reference from a provider's list call. Its URL, image and
 * summary need a separate {@link NewsProvider#details} call.
 */
public record ProviderRef(String providerId, String title, Instant publishedAt, String publisher) {
}
