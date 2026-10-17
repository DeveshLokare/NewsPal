package com.newspal.auth;

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
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth tests against a real Postgres (Testcontainers).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void clearUsers() {
		this.jdbc.update("DELETE FROM users");
	}

	@Test
	void registerReturnsTokenAndUser() throws Exception {
		register("Alice@Example.com ", "password123").andExpect(status().isCreated())
			.andExpect(jsonPath("$.token").value(notNullValue()))
			.andExpect(jsonPath("$.expiresAt").value(notNullValue()))
			.andExpect(jsonPath("$.user.email").value("alice@example.com"));
	}

	@Test
	void registerRejectsDuplicateEmailIgnoringCase() throws Exception {
		register("bob@example.com", "password123").andExpect(status().isCreated());
		register("BOB@example.com", "password456").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
	}

	@Test
	void registerValidatesInput() throws Exception {
		register("not-an-email", "short").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("email")))
			.andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("password")));
	}

	@Test
	void malformedJsonIsValidationError() throws Exception {
		this.mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{oops"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void loginSucceedsWithCorrectPassword() throws Exception {
		register("carol@example.com", "password123").andExpect(status().isCreated());
		login("CAROL@example.com", "password123").andExpect(status().isOk())
			.andExpect(jsonPath("$.token").value(notNullValue()))
			.andExpect(jsonPath("$.user.email").value("carol@example.com"));
	}

	@Test
	void loginFailsWithSameErrorForWrongPasswordOrUnknownEmail() throws Exception {
		register("dave@example.com", "password123").andExpect(status().isCreated());
		login("dave@example.com", "wrong-password").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
		login("nobody@example.com", "password123").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
	}

	@Test
	void meRequiresValidToken() throws Exception {
		this.mvc.perform(get("/api/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		this.mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-real-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void meReturnsCurrentUser() throws Exception {
		String body = register("erin@example.com", "password123").andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.token");
		this.mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("erin@example.com"));
	}

	@Test
	void healthIsPublic() throws Exception {
		this.mvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	private ResultActions register(String email, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content(credentials(email, password)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(credentials(email, password)));
	}

	private static String credentials(String email, String password) {
		return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
	}

}
