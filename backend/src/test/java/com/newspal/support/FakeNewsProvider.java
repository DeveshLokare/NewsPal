package com.newspal.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.newspal.provider.NewsProvider;
import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderException;
import com.newspal.provider.ProviderPage;

/**
 * Scriptable {@link NewsProvider} for tests.
 */
public class FakeNewsProvider implements NewsProvider {

	public final AtomicInteger calls = new AtomicInteger();

	public final AtomicInteger olderCalls = new AtomicInteger();

	public final List<String> queries = new ArrayList<>();

	/** Provider cursors passed to {@link #fetchOlder}, in order ({@code null} = first page). */
	public final List<String> olderCursors = new ArrayList<>();

	public volatile boolean enabled = true;

	public volatile boolean fail;

	public volatile List<ProviderArticle> nextResult = List.of();

	public volatile List<ProviderPage> olderPages = List.of();

	public synchronized void reset() {
		this.calls.set(0);
		this.olderCalls.set(0);
		this.queries.clear();
		this.olderCursors.clear();
		this.enabled = true;
		this.fail = false;
		this.nextResult = List.of();
		this.olderPages = List.of();
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

	/** Returns {@code olderPages} in order, one per call. */
	@Override
	public synchronized ProviderPage fetchOlder(String query, Instant before, Instant notBefore, String cursor) {
		int index = this.olderCalls.getAndIncrement();
		this.olderCursors.add(cursor);
		if (this.fail) {
			throw new ProviderException("simulated failure");
		}
		return (index < this.olderPages.size()) ? this.olderPages.get(index) : new ProviderPage(List.of(), null);
	}

}
