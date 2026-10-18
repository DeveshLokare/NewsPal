package com.newspal.archive;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import com.newspal.archive.dto.ArchiveDtos.ArchiveItem;
import com.newspal.common.CursorCodec;
import com.newspal.provider.ProviderArticle;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ArchiveRepository {

	private static final String COLUMNS = "id, url, title, summary, image_url, source, published_at, category, archived_at";

	private final JdbcClient jdbc;

	public ArchiveRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts the article unless the user already archived this URL.
	 * @return the new row, or empty if it already existed
	 */
	public Optional<ArchiveItem> insertIfAbsent(long userId, String urlHash, ProviderArticle article, String category) {
		return this.jdbc.sql("""
				INSERT INTO archived_articles
				  (user_id, url_hash, url, title, summary, image_url, source, published_at, category)
				VALUES (:user, :hash, :url, :title, :summary, :image, :source, :published, :category)
				ON CONFLICT (user_id, url_hash) DO NOTHING""" + " RETURNING " + COLUMNS)
			.param("user", userId)
			.param("hash", urlHash)
			.param("url", article.url())
			.param("title", article.title())
			.param("summary", article.summary())
			.param("image", article.imageUrl())
			.param("source", article.source())
			.param("published", (article.publishedAt() != null) ? Timestamp.from(article.publishedAt()) : null)
			.param("category", category)
			.query(ArchiveRepository::map)
			.optional();
	}

	public Optional<ArchiveItem> findByHash(long userId, String urlHash) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM archived_articles WHERE user_id = ? AND url_hash = ?")
			.params(userId, urlHash)
			.query(ArchiveRepository::map)
			.optional();
	}

	/** Newest-archived first, keyset-paginated on (archived_at, id). */
	public List<ArchiveItem> page(long userId, CursorCodec.Position after, int limit) {
		String sql = "SELECT " + COLUMNS + " FROM archived_articles WHERE user_id = :user"
				+ ((after != null) ? " AND (archived_at, id) < (:afterAt, :afterId)" : "")
				+ " ORDER BY archived_at DESC, id DESC LIMIT :limit";
		JdbcClient.StatementSpec statement = this.jdbc.sql(sql).param("user", userId).param("limit", limit);
		if (after != null) {
			statement = statement.param("afterAt", Timestamp.from(after.at())).param("afterId", after.id());
		}
		return statement.query(ArchiveRepository::map).list();
	}

	public boolean delete(long userId, long id) {
		return this.jdbc.sql("DELETE FROM archived_articles WHERE id = ? AND user_id = ?").params(id, userId).update() == 1;
	}

	public boolean deleteByHash(long userId, String urlHash) {
		return this.jdbc.sql("DELETE FROM archived_articles WHERE user_id = ? AND url_hash = ?")
			.params(userId, urlHash)
			.update() == 1;
	}

	private static ArchiveItem map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp published = rs.getTimestamp("published_at");
		return new ArchiveItem(rs.getLong("id"), rs.getString("url"), rs.getString("title"), rs.getString("summary"),
				rs.getString("image_url"), rs.getString("source"), (published != null) ? published.toInstant() : null,
				rs.getString("category"), rs.getTimestamp("archived_at").toInstant());
	}

}
