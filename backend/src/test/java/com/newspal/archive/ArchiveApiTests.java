package com.newspal.archive;

import com.jayway.jsonpath.JsonPath;
import com.newspal.TestcontainersConfiguration;
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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({ TestcontainersConfiguration.class, FakeProviderConfig.class })
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArchiveApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	private String bearer;

	@BeforeEach
	void setUp() throws Exception {
		this.jdbc.sql("DELETE FROM users").update();
		this.bearer = TestUsers.register(this.mvc, "archive@example.com");
	}

	@Test
	void archiveIsIdempotentByNormalizedUrl() throws Exception {
		String body = archive(this.bearer, """
				{"url":"https://example.com/story","title":"  A story ","summary":"Line one\\n\\n line two",
				 "imageUrl":"javascript:alert(1)","source":"example.com",
				 "publishedAt":"2026-10-17T05:00:00Z","category":"technology"}""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(notNullValue()))
			.andExpect(jsonPath("$.title").value("A story"))
			.andExpect(jsonPath("$.summary").value("Line one line two"))
			.andExpect(jsonPath("$.imageUrl").value(nullValue()))
			.andExpect(jsonPath("$.category").value("technology"))
			.andExpect(jsonPath("$.archivedAt").value(notNullValue()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		int id = JsonPath.read(body, "$.id");
		archive(this.bearer, "{\"url\":\"https://EXAMPLE.com/story/?utm_source=x\",\"title\":\"Again\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.title").value("A story"));
	}

	@Test
	void listsNewestFirstWithPagination() throws Exception {
		archive(this.bearer, "{\"url\":\"https://example.com/1\",\"title\":\"One\"}").andExpect(status().isCreated());
		archive(this.bearer, "{\"url\":\"https://example.com/2\",\"title\":\"Two\"}").andExpect(status().isCreated());
		String body = this.mvc.perform(get("/api/archive").header("Authorization", this.bearer).param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].title").value(contains("Two")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String cursor = JsonPath.read(body, "$.nextCursor");
		this.mvc.perform(get("/api/archive").header("Authorization", this.bearer).param("size", "1").param("cursor", cursor))
			.andExpect(jsonPath("$.items[*].title").value(contains("One")))
			.andExpect(jsonPath("$.nextCursor").value(nullValue()));
	}

	@Test
	void deleteOnlyOwnItems() throws Exception {
		String body = archive(this.bearer, "{\"url\":\"https://example.com/d\",\"title\":\"D\"}").andReturn()
			.getResponse()
			.getContentAsString();
		int id = JsonPath.read(body, "$.id");
		String other = TestUsers.register(this.mvc, "other@example.com");
		this.mvc.perform(delete("/api/archive/" + id).header("Authorization", other))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		this.mvc.perform(delete("/api/archive/" + id).header("Authorization", this.bearer))
			.andExpect(status().isNoContent());
		this.mvc.perform(delete("/api/archive/" + id).header("Authorization", this.bearer))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/archive").header("Authorization", this.bearer)).andExpect(jsonPath("$.items", hasSize(0)));
	}

	@Test
	void validatesInput() throws Exception {
		archive(this.bearer, "{\"url\":\"https://example.com/x\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		archive(this.bearer, "{\"url\":\"javascript:alert(1)\",\"title\":\"X\"}").andExpect(status().isBadRequest());
	}

	@Test
	void unknownCategoryIsDropped() throws Exception {
		archive(this.bearer, "{\"url\":\"https://example.com/c\",\"title\":\"C\",\"category\":\"astrology\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.category").value(nullValue()));
	}

	@Test
	void archiveRequiresAuth() throws Exception {
		this.mvc.perform(get("/api/archive")).andExpect(status().isUnauthorized());
	}

	private ResultActions archive(String bearer, String json) throws Exception {
		return this.mvc.perform(post("/api/archive").header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

}
