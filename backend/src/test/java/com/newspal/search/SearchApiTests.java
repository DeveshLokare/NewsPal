package com.newspal.search;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.jayway.jsonpath.JsonPath;
import com.newspal.TestcontainersConfiguration;
import com.newspal.common.UrlUtils;
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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/search} against a real Postgres (SPEC-v2 §8.1).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SearchService searchService;

	private String bearer;

	@BeforeEach
	void setUp() throws Exception {
		this.jdbc.sql("DELETE FROM articles").update();
		this.jdbc.sql("DELETE FROM users").update();
		this.searchService.clearLimits();
		// Follows only sports: search still covers every category.
		this.bearer = TestUsers.register(this.mvc, "search@example.com");
		TestUsers.follow(this.mvc, this.bearer, "sports");
		article("tesla", "technology", "Tesla launches a cheaper Model 2", "The carmaker unveiled it today.", 1);
		article("apple", "technology", "Apple and Samsung trade blows over foldables",
				"Both phone makers launched new devices.", 2);
		article("samsung", "technology", "Samsung's foldable sells out", "Stock ran out within hours.", 3);
		article("derby", "sports", "Chelsea beat Tottenham in the London derby", "A late goal settled it.", 4);
		article("rocket", "science", "SpaceX rocket launch delayed by weather",
				"Strong winds kept the Falcon 9 on the pad.", 5);
	}

	@Test
	void matchesTitlesAndSummariesNewestFirstWithStemming() throws Exception {
		search("launch").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].url").value(contains(url("tesla"), url("apple"), url("rocket"))))
			.andExpect(jsonPath("$.items[0].category").value("technology"))
			.andExpect(jsonPath("$.items[0].archived").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
			.andExpect(jsonPath("$.lastRefreshedAt").doesNotExist());
	}

	@Test
	void findsAWordWhileItIsStillBeingTyped() throws Exception {
		search("tesl").andExpect(jsonPath("$.items[*].url").value(contains(url("tesla"))));
		search("chelsea tott").andExpect(jsonPath("$.items[*].url").value(contains(url("derby"))));
	}

	@Test
	void supportsQuotesExclusionAndOr() throws Exception {
		search("\"london derby\"").andExpect(jsonPath("$.items[*].url").value(contains(url("derby"))));
		search("foldable -apple").andExpect(jsonPath("$.items[*].url").value(contains(url("samsung"))));
		search("tesla or chelsea")
			.andExpect(jsonPath("$.items[*].url").value(containsInAnyOrder(url("tesla"), url("derby"))));
	}

	@Test
	void filtersByAnyCategoryFollowedOrNot() throws Exception {
		search("launch", "science").andExpect(jsonPath("$.items[*].url").value(contains(url("rocket"))));
		search("launch", "nonsense").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void stopWordsOnlyMatchNothing() throws Exception {
		search("the").andExpect(status().isOk()).andExpect(jsonPath("$.items", empty()));
	}

	@Test
	void pagesWithACursor() throws Exception {
		String body = this.mvc.perform(get("/api/search").header("Authorization", this.bearer)
			.param("q", "launch")
			.param("size", "2"))
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String cursor = JsonPath.read(body, "$.nextCursor");
		this.mvc.perform(get("/api/search").header("Authorization", this.bearer)
			.param("q", "launch")
			.param("size", "2")
			.param("cursor", cursor))
			.andExpect(jsonPath("$.items[*].url").value(contains(url("rocket"))))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void showsTheUsersArchivedFlag() throws Exception {
		this.mvc.perform(post("/api/archive").header("Authorization", this.bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + url("tesla") + "\",\"title\":\"Tesla\"}")).andExpect(status().isCreated());
		search("tesla").andExpect(jsonPath("$.items[0].archived").value(true));
	}

	@Test
	void validatesTheRequest() throws Exception {
		this.mvc.perform(get("/api/search").header("Authorization", this.bearer))
			.andExpect(status().isBadRequest());
		search(" a ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		search("x".repeat(101)).andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/search").header("Authorization", this.bearer).param("q", "tesla").param("size", "0"))
			.andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/search").header("Authorization", this.bearer)
			.param("q", "tesla")
			.param("cursor", "garbage")).andExpect(jsonPath("$.code").value("BAD_CURSOR"));
	}

	@Test
	void needsSignIn() throws Exception {
		this.mvc.perform(get("/api/search").param("q", "tesla")).andExpect(status().isUnauthorized());
	}

	@Test
	void limitsSearchesPerMinute() throws Exception {
		for (int i = 0; i < SearchService.PER_MINUTE; i++) {
			search("tesla").andExpect(status().isOk());
		}
		search("tesla").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("SEARCH_RATE_LIMITED"));
	}

	private ResultActions search(String q) throws Exception {
		return this.mvc.perform(get("/api/search").header("Authorization", this.bearer).param("q", q));
	}

	private ResultActions search(String q, String category) throws Exception {
		return this.mvc.perform(get("/api/search").header("Authorization", this.bearer)
			.param("q", q)
			.param("category", category));
	}

	private void article(String key, String category, String title, String summary, int hoursAgo) {
		this.jdbc.sql("""
				INSERT INTO articles (url_hash, url, title, summary, published_at, category_id)
				VALUES (?, ?, ?, ?, ?, (SELECT id FROM categories WHERE slug = ?))""")
			.params(UrlUtils.hash(url(key)), url(key), title, summary,
					Timestamp.from(Instant.now().minus(hoursAgo, ChronoUnit.HOURS)), category)
			.update();
	}

	private static String url(String key) {
		return "https://example.com/" + key;
	}

}
