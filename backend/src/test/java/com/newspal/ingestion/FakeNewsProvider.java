package com.newspal.ingestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.newspal.provider.NewsProvider;
import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderException;

/**
 * Scriptable {@link NewsProvider} for tests.
 */
class FakeNewsProvider implements NewsProvider {

	final AtomicInteger calls = new AtomicInteger();

	final List<String> queries = new ArrayList<>();

	volatile boolean enabled = true;

	volatile boolean fail;

	volatile List<ProviderArticle> nextResult = List.of();

	void reset() {
		this.calls.set(0);
		this.queries.clear();
		this.enabled = true;
		this.fail = false;
		this.nextResult = List.of();
	}

	@Override
	public boolean isEnabled() {
		return this.enabled;
	}

	@Override
	public synchronized List<ProviderArticle> fetchLatest(String query, Instant since) {
		this.calls.incrementAndGet();
		this.queries.add(query);
		if (this.fail) {
			throw new ProviderException("simulated failure");
		}
		return this.nextResult;
	}

}
