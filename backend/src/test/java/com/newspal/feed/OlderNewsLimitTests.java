package com.newspal.feed;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.newspal.TestcontainersConfiguration;
import com.newspal.support.FakeNewsProvider;
import com.newspal.support.FakeNewsProvider.Page;
import com.newspal.support.FakeProviderConfig;
import com.newspal.support.TestUsers;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-user daily limit on cache-missing older-news lookups (own context: limit = 2).
 */
@Import({ TestcontainersConfiguration.class, FakeProviderConfig.class })
@SpringBootTest(properties = "newspal.news.older.per-user-daily=2")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OlderNewsLimitTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private FakeNewsProvider provider;

	@Test
	void thirdCacheMissInADayIsRateLimited() throws Exception {
		this.provider.reset();
		this.jdbc.sql("DELETE FROM users").update();
		this.jdbc.sql("DELETE FROM api_usage").update();
		String bearer = TestUsers.register(this.mvc, "limit@example.com");
		TestUsers.follow(this.mvc, bearer, "technology");
		this.provider.olderPages = List.of(
				new Page(List.of(OlderNewsTests.article("https://example.com/a")), "/api/news?from=10"),
				new Page(List.of(OlderNewsTests.article("https://example.com/b")), "/api/news?from=20"),
				new Page(List.of(OlderNewsTests.article("https://example.com/c")), null));

		String cursor = nextCursor(bearer, null);
		cursor = nextCursor(bearer, cursor);
		this.mvc.perform(get("/api/feed/older").header("Authorization", bearer)
			.param("category", "technology")
			.param("cursor", cursor))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("OLDER_RATE_LIMITED"));

		// Cached pages don't count against the limit.
		this.mvc.perform(get("/api/feed/older").header("Authorization", bearer).param("category", "technology"))
			.andExpect(status().isOk());
	}

	private String nextCursor(String bearer, String cursor) throws Exception {
		var request = get("/api/feed/older").header("Authorization", bearer).param("category", "technology");
		if (cursor != null) {
			request = request.param("cursor", cursor);
		}
		String body = this.mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.nextCursor");
	}

}
