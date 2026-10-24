package com.newspal.feed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.newspal.TestcontainersConfiguration;
import com.newspal.ingestion.QuotaService;
import com.newspal.ingestion.QuotaService.Bucket;
import com.newspal.provider.ProviderArticle;
import com.newspal.support.FakeNewsProvider;
import com.newspal.support.FakeNewsProvider.Page;
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
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
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
class OlderNewsTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private FakeNewsProvider provider;

	@Autowired
	private OlderNewsService olderNews;

	@Autowired
	private QuotaService quota;

	private String bearer;

	@BeforeEach
	void setUp() throws Exception {
		this.provider.reset();
		this.olderNews.clearCaches();
		this.jdbc.sql("DELETE FROM users").update();
		this.jdbc.sql("DELETE FROM api_usage").update();
		this.jdbc.sql("UPDATE categories SET last_fetched_at = now()").update();
		this.bearer = TestUsers.register(this.mvc, "older@example.com");
		TestUsers.follow(this.mvc, this.bearer, "technology", "sports");
		this.provider.olderPages = List.of(
				new Page(List.of(article("https://example.com/old1"),
						new ProviderArticle(null, "https://dailymotion.com/v", "Video", null, null, "dm", daysAgo(9))),
						"/api/news?token=secret&from=10"),
				new Page(List.of(article("https://example.com/old2")), null));
	}

	@Test
	void pagesThroughOlderNewsWithServerSideCursor() throws Exception {
		String body = older(this.bearer, "technology", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].url").value("https://example.com/old1"))
			.andExpect(jsonPath("$.items[0].id").value(nullValue()))
			.andExpect(jsonPath("$.items[0].category").value("technology"))
			.andExpect(jsonPath("$.items[0].archived").value(false))
			.andExpect(jsonPath("$.nextCursor").value(notNullValue()))
			.andExpect(jsonPath("$.lastRefreshedAt").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String cursor = JsonPath.read(body, "$.nextCursor");
		assertThat(cursor).doesNotContain("secret").doesNotContain("api/news");
		older(this.bearer, "technology", cursor).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].url").value("https://example.com/old2"))
			.andExpect(jsonPath("$.nextCursor").value(nullValue()));
		assertThat(this.provider.olderCursors).isEqualTo(Arrays.asList(null, "/api/news?token=secret&from=10"));
		assertThat(this.quota.used(Bucket.OLDER)).as("2 lists + 3 details").isEqualTo(5);
	}

	@Test
	void usersShareCachedPages() throws Exception {
		String other = TestUsers.register(this.mvc, "older2@example.com");
		TestUsers.follow(this.mvc, other, "technology");
		older(this.bearer, "technology", null).andExpect(status().isOk());
		older(other, "technology", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].url").value("https://example.com/old1"));
		assertThat(this.provider.olderCalls).hasValue(1);
		assertThat(this.quota.used(Bucket.OLDER)).as("1 list + 2 details").isEqualTo(3);
	}

	@Test
	void archivedFlagIsPerUserEvenOnCachedPages() throws Exception {
		older(this.bearer, "technology", null).andExpect(jsonPath("$.items[0].archived").value(false));
		this.mvc.perform(post("/api/archive").header("Authorization", this.bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://example.com/old1\",\"title\":\"Old\"}")).andExpect(status().isCreated());
		older(this.bearer, "technology", null).andExpect(jsonPath("$.items[0].archived").value(true));
	}

	@Test
	void requiresAFollowedCategory() throws Exception {
		older(this.bearer, null, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		older(this.bearer, "health", null).andExpect(status().isBadRequest());
		assertThat(this.provider.olderCalls).hasValue(0);
	}

	@Test
	void unknownOrMismatchedCursorIsRejected() throws Exception {
		older(this.bearer, "technology", "not-a-cursor").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_CURSOR"));
		String body = older(this.bearer, "technology", null).andReturn().getResponse().getContentAsString();
		String cursor = JsonPath.read(body, "$.nextCursor");
		older(this.bearer, "sports", cursor).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_CURSOR"));
	}

	@Test
	void exhaustedQuotaOrDisabledProviderIsUnavailable() throws Exception {
		setOlderUsed(1000);
		older(this.bearer, "technology", null).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("QUOTA_EXHAUSTED"));
		this.provider.enabled = false;
		older(this.bearer, "sports", null).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("QUOTA_EXHAUSTED"));
		assertThat(this.provider.olderCalls).hasValue(0);
	}

	@Test
	void providerFailureIsBadGatewayAndNotCached() throws Exception {
		this.provider.fail = true;
		older(this.bearer, "technology", null).andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("PROVIDER_ERROR"));
		this.provider.fail = false;
		older(this.bearer, "technology", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items", not(hasSize(0))));
	}

	@Test
	void quotaThatCantCoverThePageDetailsIsUnavailable() throws Exception {
		// The list call fits, but the page's two details calls don't.
		setOlderUsed(1000 - 2);
		older(this.bearer, "technology", null).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("QUOTA_EXHAUSTED"));
		assertThat(this.provider.detailCalls).hasValue(0);
		assertThat(this.quota.used(Bucket.OLDER)).isEqualTo(1000 - 1);
	}

	private void setOlderUsed(int calls) {
		this.jdbc.sql("""
				INSERT INTO api_usage (period, bucket, calls)
				VALUES (to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD'), 'older', ?)""").param(calls).update();
	}

	private ResultActions older(String bearer, String category, String cursor) throws Exception {
		var request = get("/api/feed/older").header("Authorization", bearer);
		if (category != null) {
			request = request.param("category", category);
		}
		if (cursor != null) {
			request = request.param("cursor", cursor);
		}
		return this.mvc.perform(request);
	}

	static ProviderArticle article(String url) {
		return new ProviderArticle(null, url, "Older story", "An older summary", "https://example.com/img.jpg",
				"example.com", daysAgo(10));
	}

	static Instant daysAgo(int days) {
		return Instant.now().minus(days, ChronoUnit.DAYS);
	}

}
