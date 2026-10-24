package com.newspal.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.newspal.article.ArticleStore;
import com.newspal.category.Category;
import com.newspal.category.CategoryRepository;
import com.newspal.config.NewsProperties;
import com.newspal.ingestion.QuotaService.Bucket;
import com.newspal.provider.NewsProvider;
import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderException;
import com.newspal.provider.ProviderRef;
import com.newspal.provider.ProviderRefPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Refreshes categories from the {@link NewsProvider} (SPEC §4.3, SPEC-v2 §5.1). A
 * refresh lists new article refs, then fetches details for the ones not stored yet;
 * every provider call counts against the daily ingestion quota.
 */
@Service
public class IngestionService {

	private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);

	/** Re-list a little before the newest stored article, in case of late indexing. */
	private static final Duration SINCE_OVERLAP = Duration.ofMinutes(5);

	private final CategoryRepository categories;

	private final ArticleStore articles;

	private final QuotaService quota;

	private final NewsProvider provider;

	private final NewsProperties properties;

	private final Clock clock;

	private final TaskExecutor executor;

	public IngestionService(CategoryRepository categories, ArticleStore articles, QuotaService quota,
			NewsProvider provider, NewsProperties properties, Clock clock,
			@Qualifier("ingestionExecutor") TaskExecutor executor) {
		this.categories = categories;
		this.articles = articles;
		this.quota = quota;
		this.provider = provider;
		this.properties = properties;
		this.clock = clock;
		this.executor = executor;
	}

	/**
	 * Queues a background refresh for each stale category; returns immediately. Used
	 * by the feed endpoint so requests never wait on the provider.
	 */
	public void refreshStaleAsync(Collection<Category> candidates) {
		Instant staleBefore = staleBefore();
		for (Category category : candidates) {
			if (isStale(category, staleBefore)) {
				this.executor.execute(() -> refreshIfStale(category));
			}
		}
	}

	/** Refreshes every stale category that has at least one follower, one by one. */
	public void refreshAllStaleFollowed() {
		List<Category> stale = this.categories.findStaleFollowed(staleBefore());
		for (Category category : stale) {
			refreshIfStale(category);
		}
	}

	/**
	 * Refreshes one category if it is stale and this thread wins the claim.
	 * @return the number of new articles stored
	 */
	public int refreshIfStale(Category category) {
		if (!this.provider.isEnabled()) {
			logger.debug("News provider disabled (no API key, or paused); skipping {}", category.slug());
			return 0;
		}
		Instant now = this.clock.instant();
		if (!this.categories.claimForRefresh(category.id(), now, now.minus(this.properties.refreshTtl()))) {
			return 0;
		}
		if (!this.quota.tryAcquire(Bucket.INGESTION)) {
			// Keep the claim so we don't re-check the exhausted quota on every request.
			logger.warn("Daily ingestion quota used up; not refreshing {}", category.slug());
			return 0;
		}
		Instant newest = this.articles.newestPublishedAt(category.id());
		List<ProviderRef> refs;
		try {
			refs = listNew(category, (newest != null) ? newest.minus(SINCE_OVERLAP) : null);
		}
		catch (RuntimeException ex) {
			// Let the next request retry. The failed call still counted against the quota.
			this.categories.restoreLastFetchedAt(category.id(), category.lastFetchedAt());
			logger.warn("Refreshing {} failed: {}", category.slug(), ex.getMessage());
			return 0;
		}
		int inserted = storeDetails(category, refs);
		logger.info("Refreshed {}: {} new refs, {} articles stored", category.slug(), refs.size(), inserted);
		return inserted;
	}

	/**
	 * Lists refs that aren't stored yet, newest first, paging until a page brings
	 * nothing new, the page limit or the quota is reached. The first page's quota unit
	 * is already taken. Only a failing first page is an error; later failures keep what
	 * was listed so far.
	 */
	private List<ProviderRef> listNew(Category category, Instant since) {
		List<ProviderRef> fresh = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String cursor = null;
		for (int page = 1;; page++) {
			ProviderRefPage result;
			try {
				result = this.provider.listLatest(category.providerQuery(), since, cursor);
			}
			catch (RuntimeException ex) {
				if (page == 1) {
					throw ex;
				}
				logger.warn("Listing page {} of {} failed: {}", page, category.slug(), ex.getMessage());
				return fresh;
			}
			List<ProviderRef> unseen = result.items().stream().filter((ref) -> seen.add(ref.providerId())).toList();
			Set<String> stored = this.articles
				.existingProviderIds(unseen.stream().map(ProviderRef::providerId).toList());
			List<ProviderRef> added = unseen.stream().filter((ref) -> !stored.contains(ref.providerId())).toList();
			fresh.addAll(added);
			cursor = result.nextCursor();
			if (added.isEmpty() || cursor == null || page >= this.properties.maxListPages()
					|| !this.provider.isEnabled() || !this.quota.tryAcquire(Bucket.INGESTION)) {
				return fresh;
			}
		}
	}

	/**
	 * Fetches details for each ref, oldest first so a refresh cut short still leaves no
	 * gap before the next one's {@code since}, and stores them one by one.
	 * @return the number of new articles stored
	 */
	private int storeDetails(Category category, List<ProviderRef> refs) {
		List<ProviderRef> oldestFirst = refs.stream().sorted(Comparator.comparing(ProviderRef::publishedAt)).toList();
		int inserted = 0;
		for (ProviderRef ref : oldestFirst) {
			if (!this.provider.isEnabled()) {
				break;
			}
			if (!this.quota.tryAcquire(Bucket.INGESTION)) {
				logger.warn("Daily ingestion quota used up while refreshing {}", category.slug());
				break;
			}
			Optional<ProviderArticle> article;
			try {
				article = this.provider.details(ref.providerId());
			}
			catch (ProviderException ex) {
				logger.debug("Details for {} failed: {}", ref.providerId(), ex.getMessage());
				continue;
			}
			if (article.isPresent()) {
				inserted += this.articles.insertNew(category.id(), List.of(article.get()),
						this.properties.summaryMaxChars());
			}
		}
		return inserted;
	}

	private Instant staleBefore() {
		return this.clock.instant().minus(this.properties.refreshTtl());
	}

	private static boolean isStale(Category category, Instant staleBefore) {
		return category.lastFetchedAt() == null || category.lastFetchedAt().isBefore(staleBefore);
	}

}
