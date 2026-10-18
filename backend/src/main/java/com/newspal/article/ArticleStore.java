package com.newspal.article;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.newspal.common.UrlUtils;
import com.newspal.provider.ProviderArticle;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes and prunes rows in {@code articles}.
 */
@Repository
public class ArticleStore {

	private static final int TITLE_MAX = 1000;

	private static final int SOURCE_MAX = 200;

	private final JdbcClient jdbc;

	public ArticleStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Instant newestPublishedAt(short categoryId) {
		// max() yields a single NULL row for an empty category, which single() would reject.
		List<Timestamp> newest = this.jdbc.sql("SELECT max(published_at) FROM articles WHERE category_id = ?")
			.param(categoryId)
			.query(Timestamp.class)
			.list();
		return (newest.isEmpty() || newest.get(0) == null) ? null : newest.get(0).toInstant();
	}

	/**
	 * Inserts articles, ignoring any whose normalized URL is already stored.
	 * @return the number of new rows
	 */
	public int insertNew(short categoryId, List<ProviderArticle> articles, int summaryMaxChars) {
		int inserted = 0;
		for (ProviderArticle article : articles) {
			String summary = collapseWhitespace(article.summary());
			// Posts without any text are videos, social posts or listings, not articles.
			if (!UrlUtils.isHttpUrl(article.url()) || summary == null) {
				continue;
			}
			inserted += this.jdbc.sql("""
					INSERT INTO articles (url_hash, url, title, summary, image_url, source, published_at, category_id)
					VALUES (:hash, :url, :title, :summary, :image, :source, :published, :category)
					ON CONFLICT (url_hash) DO NOTHING""")
				.param("hash", UrlUtils.hash(article.url()))
				.param("url", article.url().trim())
				.param("title", truncate(article.title(), TITLE_MAX))
				.param("summary", truncate(summary, summaryMaxChars))
				.param("image", (article.imageUrl() != null && UrlUtils.isHttpUrl(article.imageUrl())) ? article.imageUrl() : null)
				.param("source", truncate(article.source(), SOURCE_MAX))
				.param("published", Timestamp.from(article.publishedAt()))
				.param("category", categoryId)
				.update();
		}
		return inserted;
	}

	/** Deletes articles published before {@code cutoff}. */
	public int deleteOlderThan(Instant cutoff) {
		return this.jdbc.sql("DELETE FROM articles WHERE published_at < ?").param(Timestamp.from(cutoff)).update();
	}

	/** Keeps only the newest {@code max} articles in each category. */
	public int trimPerCategory(int max) {
		return this.jdbc.sql("""
				DELETE FROM articles a USING (
				  SELECT id, row_number() OVER (PARTITION BY category_id ORDER BY published_at DESC, id DESC) AS rn
				  FROM articles) r
				WHERE a.id = r.id AND r.rn > ?""").param(max).update();
	}

	/** Snippets often contain line breaks and runs of spaces; flatten them for display. */
	static String collapseWhitespace(String text) {
		if (text == null) {
			return null;
		}
		String collapsed = text.replaceAll("\\s+", " ").strip();
		return collapsed.isEmpty() ? null : collapsed;
	}

	/**
	 * Shortens text to at most {@code max} characters, ending with an ellipsis at a
	 * word boundary when cut.
	 */
	static String truncate(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		String cut = text.substring(0, max - 1);
		boolean endsOnWord = Character.isWhitespace(text.charAt(max - 1));
		int space = cut.lastIndexOf(' ');
		if (!endsOnWord && space > max / 2) {
			cut = cut.substring(0, space);
		}
		return cut.stripTrailing() + "…";
	}

}
