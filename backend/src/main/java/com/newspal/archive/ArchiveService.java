package com.newspal.archive;

import java.util.List;
import java.util.Optional;

import com.newspal.archive.dto.ArchiveDtos.ArchiveItem;
import com.newspal.archive.dto.ArchiveDtos.ArchivePage;
import com.newspal.archive.dto.ArchiveDtos.ArchiveRequest;
import com.newspal.article.ArticleCleaner;
import com.newspal.category.Category;
import com.newspal.category.CategoryRepository;
import com.newspal.common.ApiException;
import com.newspal.common.CursorCodec;
import com.newspal.common.ErrorCodes;
import com.newspal.common.UrlUtils;
import com.newspal.config.NewsProperties;
import com.newspal.provider.ProviderArticle;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Users' archived articles (SPEC §7.5). Archives are full copies, so they outlive the
 * feed's retention.
 */
@Service
public class ArchiveService {

	static final int MAX_PAGE_SIZE = 50;

	private final ArchiveRepository archive;

	private final CategoryRepository categories;

	private final NewsProperties properties;

	public ArchiveService(ArchiveRepository archive, CategoryRepository categories, NewsProperties properties) {
		this.archive = archive;
		this.categories = categories;
		this.properties = properties;
	}

	/** Archives an article; {@code created} is false if the URL was already archived. */
	@Transactional
	public Result archive(long userId, ArchiveRequest request) {
		if (!UrlUtils.isHttpUrl(request.url())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "url must be an http(s) URL");
		}
		// The server computes the hash itself; clients are never trusted with it.
		String hash = UrlUtils.hash(request.url());
		String image = (request.imageUrl() != null && UrlUtils.isHttpUrl(request.imageUrl())) ? request.imageUrl()
				: null;
		ProviderArticle article = new ProviderArticle(null, request.url().trim(),
				ArticleCleaner.truncate(request.title().strip(), ArticleCleaner.TITLE_MAX),
				ArticleCleaner.truncate(ArticleCleaner.collapseWhitespace(request.summary()),
						this.properties.summaryMaxChars()),
				image, ArticleCleaner.truncate(request.source(), ArticleCleaner.SOURCE_MAX), request.publishedAt());
		String category = knownCategory(request.category());
		Optional<ArchiveItem> created = this.archive.insertIfAbsent(userId, hash, article, category);
		if (created.isPresent()) {
			return new Result(created.get(), true);
		}
		return new Result(this.archive.findByHash(userId, hash).orElseThrow(), false);
	}

	@Transactional(readOnly = true)
	public ArchivePage page(long userId, String cursor, int size) {
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
					"size must be between 1 and " + MAX_PAGE_SIZE);
		}
		List<ArchiveItem> rows = this.archive.page(userId, CursorCodec.decode(cursor), size + 1);
		if (rows.size() <= size) {
			return new ArchivePage(rows, null);
		}
		List<ArchiveItem> items = List.copyOf(rows.subList(0, size));
		ArchiveItem last = items.get(items.size() - 1);
		return new ArchivePage(items, CursorCodec.encode(last.archivedAt(), last.id()));
	}

	@Transactional
	public void delete(long userId, long id) {
		if (!this.archive.delete(userId, id)) {
			// Same answer for "doesn't exist" and "belongs to someone else".
			throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Archived article not found");
		}
	}

	/**
	 * Un-archives by article URL (normalized like on archive), for the feed's bookmark
	 * toggle, which doesn't know the archive id. Idempotent: removing something that
	 * isn't archived is not an error.
	 */
	@Transactional
	public void deleteByUrl(long userId, String url) {
		if (url == null || !UrlUtils.isHttpUrl(url)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "url must be an http(s) URL");
		}
		this.archive.deleteByHash(userId, UrlUtils.hash(url));
	}

	private String knownCategory(String slug) {
		if (slug == null) {
			return null;
		}
		return this.categories.findAll().stream().map(Category::slug).filter(slug::equals).findFirst().orElse(null);
	}

	public record Result(ArchiveItem item, boolean created) {
	}

}
