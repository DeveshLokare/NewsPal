package com.newspal.feed;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.newspal.TestcontainersConfiguration;
import com.newspal.common.UrlUtils;
import com.newspal.support.FakeNewsProvider;
import com.newspal.support.FakeProviderConfig;
import com.newspal.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({ TestcontainersConfiguration.class, FakeProviderConfig.class })
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private FakeNewsProvider provider;

	private String bearer;

	@BeforeEach
	void setUp() throws Exception {
		this.provider.reset();
		this.jdbc.sql("DELETE FROM articles").update();
		this.jdbc.sql("DELETE FROM users").update();
		this.jdbc.sql("DELETE FROM api_usage").update();
		// Fresh categories, so the feed doesn't trigger background refreshes.
		this.jdbc.sql("UPDATE categories SET last_fetched_at = now()").update();
		this.bearer = TestUsers.register(this.mvc, "feed@example.com");
		TestUsers.follow(this.mvc, this.bearer, "technology", "sports");
		insert("https://example.com/t1", "technology", hoursAgo(1));
		insert("https://example.com/s1", "sports", hoursAgo(2));
		insert("https://example.com/t2", "technology", hoursAgo(3));
		insert("https://example.com/h1", "health", hoursAgo(0));
	}

	@Test
	void feedShowsFollowedCategoriesNewestFirst() throws Exception {
		this.mvc.perform(get("/api/feed").header("Authorization", this.bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].url").value(
					contains("https://example.com/t1", "https://example.com/s1", "https://example.com/t2")))
			.andExpect(jsonPath("$.items[0].category").value("technology"))
			.andExpect(jsonPath("$.items[0].id").value(notNullValue()))
			.andExpect(jsonPath("$.items[0].archived").value(false))
			.andExpect(jsonPath("$.nextCursor").value(nullValue()))
			.andExpect(jsonPath("$.lastRefreshedAt").value(notNullValue()));
	}

	@Test
	void paginatesWithoutGapsOrDuplicates() throws Exception {
		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			var request = get("/api/feed").header("Authorization", this.bearer).param("size", "2");
			if (cursor != null) {
				request = request.param("cursor", cursor);
			}
			String body = this.mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			seen.addAll(JsonPath.read(body, "$.items[*].url"));
			cursor = JsonPath.read(body, "$.nextCursor");
			pages++;
		}
		while (cursor != null);
		assertThat(pages).isEqualTo(2);
		assertThat(seen).containsExactly("https://example.com/t1", "https://example.com/s1", "https://example.com/t2");
	}

	@Test
	void filtersByFollowedCategoryOnly() throws Exception {
		this.mvc.perform(get("/api/feed").header("Authorization", this.bearer).param("category", "sports"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].url").value("https://example.com/s1"));
		this.mvc.perform(get("/api/feed").header("Authorization", this.bearer).param("category", "health"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void rejectsBadCursorAndSize() throws Exception {
		this.mvc.perform(get("/api/feed").header("Authorization", this.bearer).param("cursor", "garbage!"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_CURSOR"));
		this.mvc.perform(get("/api/feed").header("Authorization", this.bearer).param("size", "500"))
			.andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/feed").header("Authorization", this.bearer).param("size", "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void archivedFlagReflectsUsersArchive() throws Exception {
		this.mvc.perform(post("/api/archive").header("Authorization", this.bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://example.com/t1?utm_source=app\",\"title\":\"T1\"}"))
			.andExpect(status().isCreated());
		this.mvc.perform(get("/api/feed").header("Authorization", this.bearer))
			.andExpect(jsonPath("$.items[0].archived").value(true))
			.andExpect(jsonPath("$.items[1].archived").value(false));
	}

	@Test
	void userWithNoCategoriesGetsEmptyFeed() throws Exception {
		String other = TestUsers.register(this.mvc, "empty@example.com");
		this.mvc.perform(get("/api/feed").header("Authorization", other))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.nextCursor").value(nullValue()));
	}

	@Test
	void firstPageTriggersBackgroundRefreshOfStaleCategories() throws Exception {
		this.jdbc.sql("UPDATE categories SET last_fetched_at = NULL WHERE slug = 'sports'").update();
		this.mvc.perform(get("/api/feed").header("Authorization", this.bearer)).andExpect(status().isOk());
		long deadline = System.currentTimeMillis() + 5_000;
		while (this.provider.calls.get() == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(this.provider.calls).hasValue(1);
	}

	@Test
	void feedRequiresAuth() throws Exception {
		this.mvc.perform(get("/api/feed")).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/feed/older").param("category", "technology")).andExpect(status().isUnauthorized());
	}

	private void insert(String url, String slug, Instant publishedAt) {
		this.jdbc.sql("""
				INSERT INTO articles (url_hash, url, title, summary, published_at, category_id)
				SELECT :hash, :url, 'Title', 'Summary', :published, id FROM categories WHERE slug = :slug""")
			.param("hash", UrlUtils.hash(url))
			.param("url", url)
			.param("published", Timestamp.from(publishedAt))
			.param("slug", slug)
			.update();
	}

	private static Instant hoursAgo(int hours) {
		return Instant.now().minus(hours, ChronoUnit.HOURS);
	}

}
