package com.newspal.search;

import java.sql.Timestamp;
import java.util.List;

import com.newspal.common.CursorCodec;
import com.newspal.feed.FeedRepository;
import com.newspal.feed.dto.FeedDtos.ArticleDto;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Full-text search over stored articles ({@code articles.search_vector}, V9).
 */
@Repository
public class SearchRepository {

	private final JdbcClient jdbc;

	public SearchRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Matching articles, newest first, after {@code after} (keyset), with the user's
	 * archived flag. A query of only stop words ("the") matches nothing.
	 * @param categoryId {@code null} for every category
	 */
	public List<ArticleDto> search(long userId, SearchQuery query, Short categoryId, CursorCodec.Position after,
			int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT a.id, a.url, a.title, a.summary, a.image_url, a.source, a.published_at, c.slug,
				       (aa.id IS NOT NULL) AS archived
				FROM articles a
				JOIN categories c ON c.id = a.category_id
				LEFT JOIN archived_articles aa ON aa.user_id = :user AND aa.url_hash = a.url_hash
				WHERE a.search_vector @@ """);
		sql.append((query.prefix() != null)
				? "(websearch_to_tsquery('english', :websearch) && to_tsquery('english', :prefix || ':*'))"
				: "websearch_to_tsquery('english', :websearch)");
		if (categoryId != null) {
			sql.append(" AND a.category_id = :category");
		}
		if (after != null) {
			sql.append(" AND (a.published_at, a.id) < (:afterAt, :afterId)");
		}
		sql.append(" ORDER BY a.published_at DESC, a.id DESC LIMIT :limit");
		JdbcClient.StatementSpec statement = this.jdbc.sql(sql.toString())
			.param("user", userId)
			.param("websearch", query.websearch())
			.param("limit", limit);
		if (query.prefix() != null) {
			statement = statement.param("prefix", query.prefix());
		}
		if (categoryId != null) {
			statement = statement.param("category", categoryId);
		}
		if (after != null) {
			statement = statement.param("afterAt", Timestamp.from(after.at())).param("afterId", after.id());
		}
		return statement.query(FeedRepository::map).list();
	}

}
