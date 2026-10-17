package com.newspal.category;

import com.jayway.jsonpath.JsonPath;
import com.newspal.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private String token;

	@BeforeEach
	void registerUser() throws Exception {
		this.jdbc.update("DELETE FROM users");
		String body = this.mvc
			.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"cat@example.com\",\"password\":\"password123\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		this.token = JsonPath.read(body, "$.token");
	}

	@Test
	void listIsPublicAndOrdered() throws Exception {
		this.mvc.perform(get("/api/categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(8)))
			.andExpect(jsonPath("$[0].slug").value("technology"))
			.andExpect(jsonPath("$[0].name").value("Technology"))
			.andExpect(jsonPath("$[0].providerQuery").doesNotExist());
	}

	@Test
	void newUserFollowsNothing() throws Exception {
		this.mvc.perform(get("/api/me/categories").header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void replaceAndReadBackInCatalogOrder() throws Exception {
		putCategories("[\"sports\",\"technology\",\"sports\"]").andExpect(status().isNoContent());
		this.mvc.perform(get("/api/me/categories").header("Authorization", bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", contains("technology", "sports")));
		putCategories("[\"health\"]").andExpect(status().isNoContent());
		this.mvc.perform(get("/api/me/categories").header("Authorization", bearer()))
			.andExpect(jsonPath("$", contains("health")));
	}

	@Test
	void rejectsEmptyOrUnknown() throws Exception {
		putCategories("[]").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		putCategories("[\"technology\",\"astrology\"]").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void myCategoriesRequireAuth() throws Exception {
		this.mvc.perform(get("/api/me/categories")).andExpect(status().isUnauthorized());
		this.mvc.perform(put("/api/me/categories").contentType(MediaType.APPLICATION_JSON).content("[\"sports\"]"))
			.andExpect(status().isUnauthorized());
	}

	private org.springframework.test.web.servlet.ResultActions putCategories(String json) throws Exception {
		return this.mvc.perform(put("/api/me/categories").header("Authorization", bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private String bearer() {
		return "Bearer " + this.token;
	}

}
