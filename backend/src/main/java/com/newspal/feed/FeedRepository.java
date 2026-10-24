package com.newspal.feed;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.newspal.common.CursorCodec;
import com.newspal.feed.dto.FeedDtos.ArticleDto;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads stored articles for a user's feed.
 */
@Repository
public class FeedRepository {

	private final JdbcClient jdbc;

	public FeedRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Articles from the given categories, newest first, after {@code after} (keyset),
	 * with the user's archived flag. Returns at most {@code limit} rows.
	 */
	public List<ArticleDto> page(long userId, Collection<Short> categoryIds, CursorCodec.Position after, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT a.id, a.url, a.title, a.summary, a.image_url, a.source, a.published_at, c.slug,
				       (aa.id IS NOT NULL) AS archived
				FROM articles a
				JOIN categories c ON c.id = a.category_id
				LEFT JOIN archived_articles aa ON aa.user_id = :user AND aa.url_hash = a.url_hash
				WHERE a.category_id IN (:categories)
				""");
		if (after != null) {
			sql.append(" AND (a.published_at, a.id) < (:afterAt, :afterId)");
		}
		sql.append(" ORDER BY a.published_at DESC, a.id DESC LIMIT :limit");
		JdbcClient.StatementSpec statement = this.jdbc.sql(sql.toString())
			.param("user", userId)
			.param("categories", categoryIds)
			.param("limit", limit);
		if (after != null) {
			statement = statement.param("afterAt", Timestamp.from(after.at())).param("afterId", after.id());
		}
		return statement.query(FeedRepository::map).list();
	}

	/** Which of these URL hashes the user has archived. */
	public Set<String> archivedHashes(long userId, Collection<String> urlHashes) {
		if (urlHashes.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(this.jdbc.sql("SELECT url_hash FROM archived_articles WHERE user_id = :user AND url_hash IN (:hashes)")
			.param("user", userId)
			.param("hashes", urlHashes)
			.query(String.class)
			.list());
	}

	/**
	 * Maps a row with {@code a.*} article columns, {@code c.slug} and an
	 * {@code archived} flag; also used by search.
	 */
	public static ArticleDto map(ResultSet rs, int rowNum) throws SQLException {
		return new ArticleDto(rs.getLong("id"), rs.getString("url"), rs.getString("title"), rs.getString("summary"),
				rs.getString("image_url"), rs.getString("source"), rs.getTimestamp("published_at").toInstant(),
				rs.getString("slug"), rs.getBoolean("archived"));
	}

}
