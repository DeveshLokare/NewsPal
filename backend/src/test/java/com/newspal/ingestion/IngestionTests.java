package com.newspal.ingestion;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;

import com.newspal.TestcontainersConfiguration;
import com.newspal.category.Category;
import com.newspal.category.CategoryRepository;
import com.newspal.ingestion.QuotaService.Bucket;
import com.newspal.provider.ProviderArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingestion, quota and cleanup against a real Postgres with a fake provider.
 */
@Import({ TestcontainersConfiguration.class, IngestionTests.FakeProviderConfig.class })
@SpringBootTest
@ActiveProfiles("test")
class IngestionTests {

	@Autowired
	private IngestionService ingestion;

	@Autowired
	private CleanupService cleanup;

	@Autowired
	private QuotaService quota;

	@Autowired
	private CategoryRepository categories;

	@Autowired
	private FakeNewsProvider provider;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void reset() {
		this.provider.reset();
		this.jdbc.sql("DELETE FROM articles").update();
		this.jdbc.sql("DELETE FROM archived_articles").update();
		this.jdbc.sql("DELETE FROM users").update();
		this.jdbc.sql("DELETE FROM api_usage").update();
		this.jdbc.sql("UPDATE categories SET last_fetched_at = NULL").update();
	}

	@Test
	void refreshStoresArticlesAndCountsOneCall() {
		this.provider.nextResult = List.of(article("https://example.com/1", hoursAgo(1)),
				article("https://example.com/2", hoursAgo(2)));
		int inserted = this.ingestion.refreshIfStale(category("technology"));
		assertThat(inserted).isEqualTo(2);
		assertThat(articleCount()).isEqualTo(2);
		assertThat(this.quota.used(Bucket.INGESTION)).isEqualTo(1);
		assertThat(category("technology").lastFetchedAt()).isNotNull();
		assertThat(this.provider.queries).containsExactly(category("technology").providerQuery());
	}

	@Test
	void freshCategoryIsNotRefetched() {
		this.ingestion.refreshIfStale(category("sports"));
		this.ingestion.refreshIfStale(category("sports"));
		assertThat(this.provider.calls).hasValue(1);
		assertThat(this.quota.used(Bucket.INGESTION)).isEqualTo(1);
	}

	@Test
	void duplicateUrlsAreStoredOnce() {
		this.provider.nextResult = List.of(article("https://example.com/story", hoursAgo(1)),
				article("https://EXAMPLE.com/story/?utm_source=rss", hoursAgo(1)));
		assertThat(this.ingestion.refreshIfStale(category("business"))).isEqualTo(1);
		markStale("health");
		this.provider.nextResult = List.of(article("https://example.com/story", hoursAgo(1)));
		assertThat(this.ingestion.refreshIfStale(category("health"))).isZero();
		assertThat(articleCount()).isEqualTo(1);
	}

	@Test
	void providerFailureRestoresTimestampSoNextRequestRetries() {
		this.provider.fail = true;
		assertThat(this.ingestion.refreshIfStale(category("science"))).isZero();
		assertThat(category("science").lastFetchedAt()).isNull();
		assertThat(this.quota.used(Bucket.INGESTION)).isEqualTo(1);
		this.provider.fail = false;
		this.ingestion.refreshIfStale(category("science"));
		assertThat(this.provider.calls).hasValue(2);
	}

	@Test
	void exhaustedQuotaSkipsTheProvider() {
		this.jdbc.sql("INSERT INTO api_usage (month, bucket, calls) VALUES (?, 'ingestion', 720)")
			.param(this.quota.currentMonth())
			.update();
		this.ingestion.refreshIfStale(category("world"));
		assertThat(this.provider.calls).hasValue(0);
		assertThat(this.quota.used(Bucket.INGESTION)).isEqualTo(720);
	}

	@Test
	void disabledProviderSpendsNoQuota() {
		this.provider.enabled = false;
		this.ingestion.refreshIfStale(category("politics"));
		assertThat(this.provider.calls).hasValue(0);
		assertThat(this.quota.used(Bucket.INGESTION)).isZero();
		assertThat(category("politics").lastFetchedAt()).isNull();
	}

	@Test
	void scheduledPassOnlyRefreshesFollowedCategories() {
		long userId = this.jdbc.sql("INSERT INTO users (email, password_hash) VALUES ('f@example.com', 'x') RETURNING id")
			.query(Long.class)
			.single();
		this.jdbc.sql("INSERT INTO user_categories (user_id, category_id) VALUES (?, ?)")
			.params(userId, category("entertainment").id())
			.update();
		this.ingestion.refreshAllStaleFollowed();
		assertThat(this.provider.queries).containsExactly(category("entertainment").providerQuery());
	}

	@Test
	void cleanupRemovesExpiredAndOverLimitButKeepsArchives() {
		short techId = category("technology").id();
		insertArticle("https://example.com/old", Instant.now().minus(Duration.ofDays(4)), techId);
		IntStream.range(0, 205).forEach((i) -> insertArticle("https://example.com/n" + i, minutesAgo(i), techId));
		long userId = this.jdbc.sql("INSERT INTO users (email, password_hash) VALUES ('a@example.com', 'x') RETURNING id")
			.query(Long.class)
			.single();
		this.jdbc.sql("""
				INSERT INTO archived_articles (user_id, url_hash, url, title, published_at)
				VALUES (?, repeat('a', 64), 'https://example.com/old', 'Old but archived', ?)""")
			.params(userId, Timestamp.from(Instant.now().minus(Duration.ofDays(10))))
			.update();

		this.cleanup.cleanUp();

		assertThat(articleCount()).isEqualTo(200);
		assertThat(this.jdbc.sql("SELECT count(*) FROM articles WHERE url = 'https://example.com/n0'")
			.query(Integer.class)
			.single()).as("newest kept").isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT count(*) FROM articles WHERE url = 'https://example.com/n204'")
			.query(Integer.class)
			.single()).as("oldest beyond cap removed").isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM archived_articles").query(Integer.class).single()).isEqualTo(1);
	}

	private Category category(String slug) {
		return this.categories.findAll().stream().filter((c) -> c.slug().equals(slug)).findFirst().orElseThrow();
	}

	private void markStale(String slug) {
		this.jdbc.sql("UPDATE categories SET last_fetched_at = NULL WHERE slug = ?").param(slug).update();
	}

	private int articleCount() {
		return this.jdbc.sql("SELECT count(*) FROM articles").query(Integer.class).single();
	}

	private void insertArticle(String url, Instant publishedAt, short categoryId) {
		this.jdbc.sql("""
				INSERT INTO articles (url_hash, url, title, published_at, category_id)
				VALUES (md5(?) || md5(?), ?, 'Title', ?, ?)""")
			.params(url, url + "#", url, Timestamp.from(publishedAt), categoryId)
			.update();
	}

	private static ProviderArticle article(String url, Instant publishedAt) {
		return new ProviderArticle(url, "Title for " + url, "Summary", null, "example.com", publishedAt);
	}

	private static Instant hoursAgo(int hours) {
		return Instant.now().minus(hours, ChronoUnit.HOURS);
	}

	private static Instant minutesAgo(int minutes) {
		return Instant.now().minus(minutes, ChronoUnit.MINUTES);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeProviderConfig {

		@Bean
		@Primary
		FakeNewsProvider fakeNewsProvider() {
			return new FakeNewsProvider();
		}

	}

}
