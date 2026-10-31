package com.newspal.search;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.newspal.category.Category;
import com.newspal.category.CategoryRepository;
import com.newspal.common.ApiException;
import com.newspal.common.CursorCodec;
import com.newspal.common.ErrorCodes;
import com.newspal.common.GlobalExceptionHandler;
import com.newspal.feed.dto.FeedDtos.ArticleDto;
import com.newspal.feed.dto.FeedDtos.FeedPage;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Searches the stored articles of every category, not just the user's (SPEC-v2 §8.1).
 * It never calls the news provider, so it spends no quota.
 */
@Service
public class SearchService {

	static final int MIN_QUERY_CHARS = 2;

	static final int MAX_QUERY_CHARS = 100;

	static final int MAX_PAGE_SIZE = 50;

	/** Searches per user per minute; the app searches as the user types. */
	static final int PER_MINUTE = 60;

	private static final Duration WINDOW = Duration.ofMinutes(1);

	private final SearchRepository search;

	private final CategoryRepository categories;

	/** User id → searches in the current minute. */
	private final Cache<Long, AtomicInteger> perUser = Caffeine.newBuilder()
		.expireAfterWrite(WINDOW)
		.maximumSize(50_000)
		.build();

	public SearchService(SearchRepository search, CategoryRepository categories) {
		this.search = search;
		this.categories = categories;
	}

	public FeedPage search(long userId, String q, String categorySlug, String cursor, int size) {
		String text = (q != null) ? q.strip() : "";
		if (text.length() < MIN_QUERY_CHARS || text.length() > MAX_QUERY_CHARS) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
					"q must be " + MIN_QUERY_CHARS + " to " + MAX_QUERY_CHARS + " characters");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
					"size must be between 1 and " + MAX_PAGE_SIZE);
		}
		Short categoryId = categoryId(categorySlug);
		CursorCodec.Position after = CursorCodec.decode(cursor);
		countSearch(userId);
		List<ArticleDto> rows = this.search.search(userId, SearchQuery.parse(text), categoryId, after, size + 1);
		boolean more = rows.size() > size;
		List<ArticleDto> items = more ? rows.subList(0, size) : rows;
		String next = null;
		if (more) {
			ArticleDto last = items.get(items.size() - 1);
			next = CursorCodec.encode(last.publishedAt(), last.id());
		}
		return new FeedPage(List.copyOf(items), next, null, null);
	}

	/** For tests: forget per-user counts. */
	void clearLimits() {
		this.perUser.invalidateAll();
	}

	/** Any known category, followed or not; {@code null} for all of them. */
	private Short categoryId(String slug) {
		if (slug == null || slug.isBlank()) {
			return null;
		}
		return this.categories.findAll()
			.stream()
			.filter((c) -> c.slug().equals(slug))
			.map(Category::id)
			.findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
					"Unknown category '" + slug + "'"));
	}

	private void countSearch(long userId) {
		AtomicInteger count = this.perUser.get(userId, (k) -> new AtomicInteger());
		if (count.incrementAndGet() > PER_MINUTE) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.SEARCH_RATE_LIMITED,
					"You're searching very fast; wait a moment",
					Map.of(GlobalExceptionHandler.RETRY_AFTER_SECONDS, WINDOW.toSeconds()));
		}
	}

}
