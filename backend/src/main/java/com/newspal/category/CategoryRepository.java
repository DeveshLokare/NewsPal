package com.newspal.category;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC access to {@code categories} and {@code user_categories}.
 */
@Repository
public class CategoryRepository {

	private static final String COLUMNS = "id, slug, name, provider_query, sort_order, last_fetched_at";

	private final JdbcClient jdbc;

	public CategoryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Category> findAll() {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM categories ORDER BY sort_order, id")
			.query(CategoryRepository::map)
			.list();
	}

	public Optional<Category> findById(short id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM categories WHERE id = ?")
			.param(id)
			.query(CategoryRepository::map)
			.optional();
	}

	public List<Category> findFollowedBy(long userId) {
		return this.jdbc.sql("""
				SELECT c.id, c.slug, c.name, c.provider_query, c.sort_order, c.last_fetched_at
				FROM categories c JOIN user_categories uc ON uc.category_id = c.id
				WHERE uc.user_id = ?
				ORDER BY c.sort_order, c.id""").param(userId).query(CategoryRepository::map).list();
	}

	/**
	 * Categories whose last fetch is older than {@code staleBefore}, followed or not:
	 * search covers every category (SPEC-v2 §5.1).
	 */
	public List<Category> findStale(Instant staleBefore) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM categories
				WHERE last_fetched_at IS NULL OR last_fetched_at < ?
				ORDER BY sort_order, id""")
			.param(Timestamp.from(staleBefore))
			.query(CategoryRepository::map)
			.list();
	}

	/**
	 * Atomically claims a category for refreshing (SPEC §4.3 step 1). Returns
	 * {@code false} if it is not stale or another thread already claimed it.
	 */
	public boolean claimForRefresh(short id, Instant now, Instant staleBefore) {
		return this.jdbc.sql("""
				UPDATE categories SET last_fetched_at = ?
				WHERE id = ? AND (last_fetched_at IS NULL OR last_fetched_at < ?)""")
			.params(Timestamp.from(now), id, Timestamp.from(staleBefore))
			.update() == 1;
	}

	/** Puts back the previous fetch time after a failed refresh so it is retried. */
	public void restoreLastFetchedAt(short id, Instant previous) {
		this.jdbc.sql("UPDATE categories SET last_fetched_at = ? WHERE id = ?")
			.params((previous != null) ? Timestamp.from(previous) : null, id)
			.update();
	}

	public List<Short> findIdsBySlugs(Collection<String> slugs) {
		if (slugs.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("SELECT id FROM categories WHERE slug IN (:slugs)")
			.param("slugs", slugs)
			.query(Short.class)
			.list();
	}

	public void replaceUserCategories(long userId, Collection<Short> categoryIds) {
		this.jdbc.sql("DELETE FROM user_categories WHERE user_id = ?").param(userId).update();
		for (Short categoryId : categoryIds) {
			this.jdbc.sql("INSERT INTO user_categories (user_id, category_id) VALUES (?, ?)")
				.params(userId, categoryId)
				.update();
		}
	}

	private static Category map(ResultSet rs, int row) throws SQLException {
		Timestamp lastFetched = rs.getTimestamp("last_fetched_at");
		return new Category(rs.getShort("id"), rs.getString("slug"), rs.getString("name"),
				rs.getString("provider_query"), rs.getShort("sort_order"),
				(lastFetched != null) ? lastFetched.toInstant() : null);
	}

}
