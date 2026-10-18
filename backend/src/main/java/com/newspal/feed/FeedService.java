package com.newspal.feed;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.newspal.category.Category;
import com.newspal.category.CategoryRepository;
import com.newspal.common.ApiException;
import com.newspal.common.CursorCodec;
import com.newspal.common.ErrorCodes;
import com.newspal.feed.dto.FeedDtos.ArticleDto;
import com.newspal.feed.dto.FeedDtos.FeedPage;
import com.newspal.ingestion.IngestionService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The stored ("hot path") feed (SPEC §4.1, §7.4).
 */
@Service
public class FeedService {

	static final int MAX_PAGE_SIZE = 50;

	private final CategoryRepository categories;

	private final FeedRepository feed;

	private final IngestionService ingestion;

	public FeedService(CategoryRepository categories, FeedRepository feed, IngestionService ingestion) {
		this.categories = categories;
		this.feed = feed;
		this.ingestion = ingestion;
	}

	public FeedPage page(long userId, String categorySlug, String cursor, int size) {
		checkSize(size);
		CursorCodec.Position after = CursorCodec.decode(cursor);
		List<Category> selected = followedCategories(userId, categorySlug);
		if (selected.isEmpty()) {
			return new FeedPage(List.of(), null, null);
		}
		if (after == null) {
			// First page: refresh stale categories in the background; never wait for webz.io.
			this.ingestion.refreshStaleAsync(selected);
		}
		List<ArticleDto> rows = this.feed.page(userId, selected.stream().map(Category::id).toList(), after, size + 1);
		boolean more = rows.size() > size;
		List<ArticleDto> items = more ? rows.subList(0, size) : rows;
		String next = null;
		if (more) {
			ArticleDto last = items.get(items.size() - 1);
			next = CursorCodec.encode(last.publishedAt(), last.id());
		}
		Instant lastRefreshedAt = selected.stream()
			.map(Category::lastFetchedAt)
			.filter(Objects::nonNull)
			.max(Instant::compareTo)
			.orElse(null);
		return new FeedPage(List.copyOf(items), next, lastRefreshedAt);
	}

	/**
	 * The user's followed categories, or just {@code slug} if given; a category the
	 * user does not follow is a 400.
	 */
	List<Category> followedCategories(long userId, String slug) {
		List<Category> followed = this.categories.findFollowedBy(userId);
		if (slug == null || slug.isBlank()) {
			return followed;
		}
		List<Category> match = followed.stream().filter((c) -> c.slug().equals(slug)).toList();
		if (match.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
					"You don't follow category '" + slug + "'");
		}
		return match;
	}

	static void checkSize(int size) {
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
					"size must be between 1 and " + MAX_PAGE_SIZE);
		}
	}

}
