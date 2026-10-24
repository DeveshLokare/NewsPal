package com.newspal.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.newspal.provider.NewsProvider;
import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderException;
import com.newspal.provider.ProviderRef;
import com.newspal.provider.ProviderRefPage;

/**
 * Scriptable {@link NewsProvider} for tests. Scripts are full articles; list calls
 * return their refs and {@link #details} looks them up by id (an article without a
 * {@code providerId} gets its URL as id).
 */
public class FakeNewsProvider implements NewsProvider {

	/** {@link #listLatest} calls. */
	public final AtomicInteger calls = new AtomicInteger();

	public final AtomicInteger olderCalls = new AtomicInteger();

	public final AtomicInteger detailCalls = new AtomicInteger();

	/** Topics passed to {@link #listLatest}, in order. */
	public final List<String> queries = new ArrayList<>();

	/** Provider cursors passed to {@link #listLatest}, in order ({@code null} = first page). */
	public final List<String> latestCursors = new ArrayList<>();

	/** Provider cursors passed to {@link #listOlder}, in order ({@code null} = first page). */
	public final List<String> olderCursors = new ArrayList<>();

	/** Ids passed to {@link #details}, in order. */
	public final List<String> detailIds = new ArrayList<>();

	public volatile boolean enabled = true;

	/** Makes list calls fail. */
	public volatile boolean fail;

	/** {@link #listLatest} calls from this 0-based index on fail. */
	public volatile int failLatestFrom = Integer.MAX_VALUE;

	/** When set, {@link #listLatest} waits for it (up to 10 s) before answering. */
	public volatile CountDownLatch latestGate;

	/** Details calls for these ids fail. */
	public final Set<String> failingDetails = ConcurrentHashMap.newKeySet();

	/** Single page returned by {@link #listLatest} when {@link #latestPages} is empty. */
	public volatile List<ProviderArticle> nextResult = List.of();

	/** Pages returned by {@link #listLatest} in order, one per call. */
	public volatile List<Page> latestPages = List.of();

	/** Pages returned by {@link #listOlder} in order, one per call. */
	public volatile List<Page> olderPages = List.of();

	private final Map<String, ProviderArticle> byId = new HashMap<>();

	public synchronized void reset() {
		this.calls.set(0);
		this.olderCalls.set(0);
		this.detailCalls.set(0);
		this.queries.clear();
		this.latestCursors.clear();
		this.olderCursors.clear();
		this.detailIds.clear();
		this.enabled = true;
		this.fail = false;
		this.failLatestFrom = Integer.MAX_VALUE;
		this.latestGate = null;
		this.failingDetails.clear();
		this.nextResult = List.of();
		this.latestPages = List.of();
		this.olderPages = List.of();
		this.byId.clear();
	}

	@Override
	public boolean isEnabled() {
		return this.enabled;
	}

	@Override
	public ProviderRefPage listLatest(String topic, Instant since, String cursor) {
		CountDownLatch gate = this.latestGate;
		if (gate != null) {
			try {
				gate.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		return listLatestNow(topic, cursor);
	}

	private synchronized ProviderRefPage listLatestNow(String topic, String cursor) {
		int index = this.calls.getAndIncrement();
		this.queries.add(topic);
		this.latestCursors.add(cursor);
		if (this.fail || index >= this.failLatestFrom) {
			throw new ProviderException("simulated failure");
		}
		if (this.latestPages.isEmpty()) {
			return toRefs(new Page(this.nextResult, null));
		}
		return toRefs((index < this.latestPages.size()) ? this.latestPages.get(index) : new Page(List.of(), null));
	}

	@Override
	public synchronized ProviderRefPage listOlder(String topic, Instant before, Instant notBefore, String cursor) {
		int index = this.olderCalls.getAndIncrement();
		this.olderCursors.add(cursor);
		if (this.fail) {
			throw new ProviderException("simulated failure");
		}
		return toRefs((index < this.olderPages.size()) ? this.olderPages.get(index) : new Page(List.of(), null));
	}

	@Override
	public synchronized Optional<ProviderArticle> details(String providerId) {
		this.detailCalls.incrementAndGet();
		this.detailIds.add(providerId);
		if (this.failingDetails.contains(providerId)) {
			throw new ProviderException("simulated details failure");
		}
		return Optional.ofNullable(this.byId.get(providerId));
	}

	private ProviderRefPage toRefs(Page page) {
		List<ProviderRef> refs = new ArrayList<>();
		for (ProviderArticle article : page.items()) {
			String id = id(article);
			this.byId.put(id, new ProviderArticle(id, article.url(), article.title(), article.summary(),
					article.imageUrl(), article.source(), article.publishedAt()));
			refs.add(new ProviderRef(id, article.title(), article.publishedAt(), article.source()));
		}
		return new ProviderRefPage(refs, page.nextCursor());
	}

	public static String id(ProviderArticle article) {
		return (article.providerId() != null) ? article.providerId() : article.url();
	}

	/** A scripted page of full articles. */
	public record Page(List<ProviderArticle> items, String nextCursor) {
	}

}
