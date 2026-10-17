package com.newspal.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.newspal.article.ArticleStore;
import com.newspal.category.Category;
import com.newspal.category.CategoryRepository;
import com.newspal.config.NewsProperties;
import com.newspal.ingestion.QuotaService.Bucket;
import com.newspal.provider.NewsProvider;
import com.newspal.provider.ProviderArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Lazily refreshes categories from the {@link NewsProvider} (SPEC §4.3).
 */
@Service
public class IngestionService {

	private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);

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
	 * by the feed endpoint so requests never wait on webz.io.
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
			logger.debug("News provider disabled (no token); skipping {}", category.slug());
			return 0;
		}
		Instant now = this.clock.instant();
		if (!this.categories.claimForRefresh(category.id(), now, now.minus(this.properties.refreshTtl()))) {
			return 0;
		}
		if (!this.quota.tryAcquire(Bucket.INGESTION)) {
			// Keep the claim so we don't re-check the exhausted quota on every request.
			logger.warn("Monthly ingestion quota used up; not refreshing {}", category.slug());
			return 0;
		}
		try {
			Instant since = this.articles.newestPublishedAt(category.id());
			List<ProviderArticle> fetched = this.provider.fetchLatest(category.providerQuery(), since);
			int inserted = this.articles.insertNew(category.id(), fetched, this.properties.summaryMaxChars());
			logger.info("Refreshed {}: {} fetched, {} new", category.slug(), fetched.size(), inserted);
			return inserted;
		}
		catch (RuntimeException ex) {
			// Let the next request retry. The failed call still counted against the quota.
			this.categories.restoreLastFetchedAt(category.id(), category.lastFetchedAt());
			logger.warn("Refreshing {} failed: {}", category.slug(), ex.getMessage());
			return 0;
		}
	}

	private Instant staleBefore() {
		return this.clock.instant().minus(this.properties.refreshTtl());
	}

	private static boolean isStale(Category category, Instant staleBefore) {
		return category.lastFetchedAt() == null || category.lastFetchedAt().isBefore(staleBefore);
	}

}
