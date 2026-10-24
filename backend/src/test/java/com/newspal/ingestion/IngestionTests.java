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
import com.newspal.config.NewsProperties;
import com.newspal.ingestion.QuotaService.Bucket;
import com.newspal.provider.ProviderArticle;
import com.newspal.support.FakeNewsProvider;
import com.newspal.support.FakeNewsProvider.Page;
import com.newspal.support.FakeProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingestion, quota and cleanup against a real Postgres with a fake provider.
 */
@Import({ TestcontainersConfiguration.class, FakeProviderConfig.class })
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

	@Autowired
	private NewsProperties properties;

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
	void refreshStoresArticlesAndCountsEveryCall() {
		this.provider.nextResult = List.of(article("https://example.com/1", hoursAgo(1)),
				article("https://example.com/2", hoursAgo(2)));
		int inserted = this.ingestion.refreshIfStale(category("technology"));
		assertThat(inserted).isEqualTo(2);
		assertThat(articleCount()).isEqualTo(2);
		assertThat(this.quota.used(Bucket.INGESTION)).as("1 list + 2 details").isEqualTo(3);
		assertThat(this.provider.detailIds).as("oldest first")
			.containsExactly("https://example.com/2", "https://example.com/1");
		assertThat(this.jdbc.sql("SELECT count(*) FROM articles WHERE provider_id IS NOT NULL")
			.query(Integer.class)
			.single()).isEqualTo(2);
		assertThat(category("technology").lastFetchedAt()).isNotNull();
		assertThat(this.provider.queries).containsExactly(category("technology").providerQuery());
	}

	@Test
	void postsWithoutTextAreSkipped() {
		this.provider.nextResult = List.of(article("https://example.com/real", hoursAgo(1)),
				new ProviderArticle(null, "https://dailymotion.com/video/x1", "Episode 4 English Sub", null, null,
						"dailymotion.com", hoursAgo(1)),
				new ProviderArticle(null, "https://example.com/blank", "Blank", " \n ", null, "example.com",
						hoursAgo(1)));
		assertThat(this.ingestion.refreshIfStale(category("entertainment"))).isEqualTo(1);
		assertThat(articleCount()).isEqualTo(1);
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
	void storedProviderIdsSkipTheDetailsCall() {
		this.provider.nextResult = List.of(article("https://example.com/a", hoursAgo(2)));
		this.ingestion.refreshIfStale(category("technology"));
		markStale("technology");
		this.provider.nextResult = List.of(article("https://example.com/b", hoursAgo(1)),
				article("https://example.com/a", hoursAgo(2)));
		assertThat(this.ingestion.refreshIfStale(category("technology"))).isEqualTo(1);
		assertThat(this.provider.detailIds).containsExactly("https://example.com/a", "https://example.com/b");
	}

	@Test
	void pagesUntilAPageBringsNothingNew() {
		this.jdbc.sql("INSERT INTO articles (url_hash, url, title, published_at, category_id, provider_id) "
				+ "VALUES (repeat('c', 64), 'https://example.com/known', 'Known', now(), ?, 'known')")
			.param(category("sports").id())
			.update();
		this.provider.latestPages = List.of(new Page(List.of(article("https://example.com/p1", hoursAgo(1))), "c1"),
				new Page(List.of(withId("known", "https://example.com/known")), "c2"),
				new Page(List.of(article("https://example.com/p3", hoursAgo(3))), null));
		assertThat(this.ingestion.refreshIfStale(category("sports"))).isEqualTo(1);
		assertThat(this.provider.latestCursors).containsExactly(null, "c1");
	}

	@Test
	void pagingStopsAtTheConfiguredLimit() {
		int maxPages = this.properties.maxListPages();
		this.provider.latestPages = IntStream.range(0, maxPages + 2)
			.mapToObj((i) -> new Page(List.of(article("https://example.com/page" + i, hoursAgo(i + 1))), "c" + i))
			.toList();
		assertThat(this.ingestion.refreshIfStale(category("business"))).isEqualTo(maxPages);
		assertThat(this.provider.calls).hasValue(maxPages);
		assertThat(this.quota.used(Bucket.INGESTION)).isEqualTo(maxPages * 2);
	}

	@Test
	void laterPageFailureKeepsWhatWasListed() {
		this.provider.latestPages = List.of(new Page(List.of(article("https://example.com/first", hoursAgo(1))), "c1"));
		this.provider.failLatestFrom = 1;
		assertThat(this.ingestion.refreshIfStale(category("health"))).isEqualTo(1);
		assertThat(category("health").lastFetchedAt()).isNotNull();
	}

	@Test
	void failedDetailsSkipOnlyThatArticle() {
		this.provider.nextResult = List.of(article("https://example.com/ok", hoursAgo(1)),
				article("https://example.com/broken", hoursAgo(2)));
		this.provider.failingDetails.add("https://example.com/broken");
		assertThat(this.ingestion.refreshIfStale(category("science"))).isEqualTo(1);
		assertThat(category("science").lastFetchedAt()).as("refresh still counts").isNotNull();
	}

	@Test
	void quotaRunningOutStopsDetailsCalls() {
		int limit = this.properties.quota().ingestionDaily();
		this.jdbc.sql("INSERT INTO api_usage (period, bucket, calls) VALUES (?, 'ingestion', ?)")
			.params(this.quota.currentPeriod(), limit - 2)
			.update();
		this.provider.nextResult = List.of(article("https://example.com/q1", hoursAgo(1)),
				article("https://example.com/q2", hoursAgo(2)), article("https://example.com/q3", hoursAgo(3)));
		assertThat(this.ingestion.refreshIfStale(category("world"))).as("1 list + 1 details").isEqualTo(1);
		assertThat(this.provider.detailCalls).hasValue(1);
		assertThat(this.quota.used(Bucket.INGESTION)).isEqualTo(limit);
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
		int limit = this.properties.quota().ingestionDaily();
		this.jdbc.sql("INSERT INTO api_usage (period, bucket, calls) VALUES (?, 'ingestion', ?)")
			.params(this.quota.currentPeriod(), limit)
			.update();
		this.ingestion.refreshIfStale(category("world"));
		assertThat(this.provider.calls).hasValue(0);
		assertThat(this.quota.used(Bucket.INGESTION)).isEqualTo(limit);
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
	void fullPassRefreshesEveryStaleCategoryFollowedOrNot() {
		this.jdbc.sql("UPDATE categories SET last_fetched_at = now() WHERE slug = 'sports'").update();
		this.ingestion.refreshAllStale();
		List<String> expected = this.categories.findAll()
			.stream()
			.filter((c) -> !c.slug().equals("sports"))
			.map(Category::providerQuery)
			.toList();
		assertThat(this.provider.queries).as("no one follows any category here").isEqualTo(expected);
	}

	@Test
	void cleanupRemovesExpiredAndOverLimitButKeepsArchives() {
		short techId = category("technology").id();
		insertArticle("https://example.com/old", Instant.now().minus(Duration.ofDays(4)), techId);
		int max = this.properties.maxPerCategory();
		IntStream.range(0, max + 5).forEach((i) -> insertArticle("https://example.com/n" + i, minutesAgo(i), techId));
		long userId = this.jdbc.sql("INSERT INTO users (email, password_hash) VALUES ('a@example.com', 'x') RETURNING id")
			.query(Long.class)
			.single();
		this.jdbc.sql("""
				INSERT INTO archived_articles (user_id, url_hash, url, title, published_at)
				VALUES (?, repeat('a', 64), 'https://example.com/old', 'Old but archived', ?)""")
			.params(userId, Timestamp.from(Instant.now().minus(Duration.ofDays(10))))
			.update();

		this.cleanup.cleanUp();

		assertThat(articleCount()).isEqualTo(max);
		assertThat(this.jdbc.sql("SELECT count(*) FROM articles WHERE url = 'https://example.com/n0'")
			.query(Integer.class)
			.single()).as("newest kept").isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT count(*) FROM articles WHERE url = ?")
			.param("https://example.com/n" + (max + 4))
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
		return new ProviderArticle(null, url, "Title for " + url, "Summary", null, "example.com", publishedAt);
	}

	private static ProviderArticle withId(String id, String url) {
		return new ProviderArticle(id, url, "Title for " + url, "Summary", null, "example.com", hoursAgo(2));
	}

	private static Instant hoursAgo(int hours) {
		return Instant.now().minus(hours, ChronoUnit.HOURS);
	}

	private static Instant minutesAgo(int minutes) {
		return Instant.now().minus(minutes, ChronoUnit.MINUTES);
	}


}
