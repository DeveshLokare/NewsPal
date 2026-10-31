package com.newspal.feed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.newspal.article.ArticleCleaner;
import com.newspal.category.Category;
import com.newspal.common.ApiException;
import com.newspal.common.CursorCodec;
import com.newspal.common.ErrorCodes;
import com.newspal.common.UrlUtils;
import com.newspal.config.NewsProperties;
import com.newspal.feed.dto.FeedDtos.ArticleDto;
import com.newspal.feed.dto.FeedDtos.FeedPage;
import com.newspal.ingestion.QuotaService;
import com.newspal.ingestion.QuotaService.Bucket;
import com.newspal.provider.NewsProvider;
import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderException;
import com.newspal.provider.ProviderRef;
import com.newspal.provider.ProviderRefPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Live "older news" beyond the stored window (SPEC §5, SPEC-v2 §4.6). Results are
 * cached, never stored. Cursors handed to the app are random ids; the provider's own
 * paging cursor stays on the server. A page costs one list call plus one details call
 * per article, all from the daily {@code older} quota.
 */
@Service
public class OlderNewsService {

	private static final Logger logger = LoggerFactory.getLogger(OlderNewsService.class);

	private final FeedService feedService;

	private final FeedRepository feed;

	private final NewsProvider provider;

	private final QuotaService quota;

	private final NewsProperties properties;

	private final Clock clock;

	/** Fetched pages by cache key (first page per category/hour, or by cursor id). */
	private final Cache<String, CachedPage> pages;

	/** Cursor id handed to the app → how to fetch the following page. */
	private final Cache<String, CursorState> cursors;

	/** "userId|date" → cache-missing lookups made that UTC day. */
	private final Cache<String, AtomicInteger> userCounts;

	public OlderNewsService(FeedService feedService, FeedRepository feed, NewsProvider provider, QuotaService quota,
			NewsProperties properties, Clock clock) {
		this.feedService = feedService;
		this.feed = feed;
		this.provider = provider;
		this.quota = quota;
		this.properties = properties;
		this.clock = clock;
		Duration ttl = properties.older().cacheTtl();
		this.pages = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(500).build();
		this.cursors = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(5_000).build();
		this.userCounts = Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(2)).build();
	}

	public FeedPage page(long userId, String categorySlug, String cursor) {
		if (categorySlug == null || categorySlug.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
					"Choose a category to load older news");
		}
		Category category = this.feedService.followedCategories(userId, categorySlug).get(0);
		String key;
		CursorState state;
		if (cursor == null || cursor.isBlank()) {
			Instant now = this.clock.instant();
			// Rounded to the hour so everyone asking within the hour shares one cached page.
			Instant before = now.minus(this.properties.retention()).truncatedTo(ChronoUnit.HOURS);
			Instant notBefore = now.minus(this.properties.older().maxAge());
			state = new CursorState(category.slug(), category.providerQuery(), before, notBefore, null);
			key = "first|" + category.slug() + "|" + before;
		}
		else {
			state = this.cursors.getIfPresent(cursor);
			if (state == null || !state.slug().equals(category.slug())) {
				throw CursorCodec.badCursor();
			}
			key = "cursor|" + cursor;
		}
		CachedPage page = this.pages.get(key, (k) -> load(userId, state));
		return toFeedPage(userId, category.slug(), page);
	}

	/** Runs at most once per key at a time (Caffeine computes atomically). */
	private CachedPage load(long userId, CursorState state) {
		if (!this.provider.isEnabled()) {
			throw unavailable();
		}
		AtomicInteger used = this.userCounts.get(userId + "|" + LocalDate.now(this.clock.withZone(ZoneOffset.UTC)),
				(k) -> new AtomicInteger());
		if (used.incrementAndGet() > this.properties.older().perUserDaily()) {
			used.decrementAndGet();
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.OLDER_RATE_LIMITED,
					"You've loaded a lot of older news today; try again tomorrow");
		}
		if (!this.quota.tryAcquire(Bucket.OLDER)) {
			used.decrementAndGet();
			throw unavailable();
		}
		ProviderRefPage result;
		try {
			result = this.provider.listOlder(state.query(), state.before(), state.notBefore(),
					state.providerCursor());
		}
		catch (ProviderException ex) {
			logger.warn("Older news for {} failed: {}", state.slug(), ex.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.PROVIDER_ERROR,
					"Couldn't load older news right now");
		}
		// Reserve the whole page's details up front so we never serve half a page.
		if (!this.quota.tryAcquire(Bucket.OLDER, result.items().size())) {
			throw unavailable();
		}
		List<ProviderArticle> items = fetchDetails(result.items());
		String nextId = null;
		if (result.nextCursor() != null) {
			nextId = UUID.randomUUID().toString();
			this.cursors.put(nextId, new CursorState(state.slug(), state.query(), state.before(), state.notBefore(),
					result.nextCursor()));
		}
		return new CachedPage(items, nextId);
	}

	/** Details for each ref, newest first; refs whose details fail or can't be shown are skipped. */
	private List<ProviderArticle> fetchDetails(List<ProviderRef> refs) {
		int maxChars = this.properties.summaryMaxChars();
		List<ProviderArticle> items = new ArrayList<>();
		for (ProviderRef ref : refs) {
			Optional<ProviderArticle> article;
			try {
				article = this.provider.details(ref.providerId());
			}
			catch (ProviderException ex) {
				logger.debug("Details for {} failed: {}", ref.providerId(), ex.getMessage());
				continue;
			}
			article.map((a) -> ArticleCleaner.clean(a, maxChars)).ifPresent(items::add);
		}
		return items;
	}

	private FeedPage toFeedPage(long userId, String slug, CachedPage page) {
		// Keyed by URL hash: drops duplicates and lets us look up the archived flags.
		Map<String, ProviderArticle> byHash = new LinkedHashMap<>();
		for (ProviderArticle article : page.items()) {
			byHash.putIfAbsent(UrlUtils.hash(article.url()), article);
		}
		Set<String> archived = this.feed.archivedHashes(userId, byHash.keySet());
		List<ArticleDto> items = byHash.entrySet()
			.stream()
			.map((e) -> {
				ProviderArticle a = e.getValue();
				return new ArticleDto(null, a.url(), a.title(), a.summary(), a.imageUrl(), a.source(),
						a.publishedAt(), slug, archived.contains(e.getKey()));
			})
			.toList();
		return new FeedPage(items, page.nextCursorId(), null, null);
	}

	/** For tests: forget cached pages, cursors and per-user counts. */
	void clearCaches() {
		this.pages.invalidateAll();
		this.cursors.invalidateAll();
		this.userCounts.invalidateAll();
	}

	private static ApiException unavailable() {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.QUOTA_EXHAUSTED,
				"Older news unavailable right now");
	}

	private record CachedPage(List<ProviderArticle> items, String nextCursorId) {
	}

	private record CursorState(String slug, String query, Instant before, Instant notBefore, String providerCursor) {
	}

}
