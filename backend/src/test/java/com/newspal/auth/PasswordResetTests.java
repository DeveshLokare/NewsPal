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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security questions and password reset (SPEC-v2 §7) against a real Postgres.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetTests {

	private static final String PET = "What was the name of your first pet?";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordResetService resets;

	private short petQuestion;

	@BeforeEach
	void reset() {
		this.jdbc.update("DELETE FROM users");
		this.resets.clearLimits();
		this.petQuestion = this.jdbc.queryForObject("SELECT id FROM security_questions WHERE text = ?", Short.class,
				PET);
	}

	@Test
	void questionsArePublic() throws Exception {
		this.mvc.perform(get("/api/auth/security-questions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(8)))
			.andExpect(jsonPath("$[0].text").value(PET));
	}

	@Test
	void registeringWithAQuestionIsReported() throws Exception {
		String bearer = register("q@example.com", "password123", this.petQuestion, "Mr. Whiskers")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.hasSecurityQuestion").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();
		this.mvc.perform(get("/api/me").header("Authorization", "Bearer " + JsonPath.read(bearer, "$.token")))
			.andExpect(jsonPath("$.hasSecurityQuestion").value(true));
		register("noq@example.com", "password123", null, null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.hasSecurityQuestion").value(false));
	}

	@Test
	void registrationChecksTheQuestionAndAnswer() throws Exception {
		register("a@example.com", "password123", this.petQuestion, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("answer")));
		register("a@example.com", "password123", (short) 999, "Whiskers").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("questionId")));
		register("a@example.com", "password123", this.petQuestion, " !! ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
	}

	@Test
	void resetWithTheRightAnswerSignsInAndSignsOutOldTokens() throws Exception {
		String oldToken = token(register("r@example.com", "password123", this.petQuestion, "Mr. Whiskers"));
		me(oldToken).andExpect(status().isOk());

		question("R@Example.com ").andExpect(status().isOk())
			.andExpect(jsonPath("$.questionId").value((int) this.petQuestion))
			.andExpect(jsonPath("$.text").value(PET));
		String newToken = token(resetPassword("r@example.com", "  mr   whiskers ", "brand-new-pass")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.email").value("r@example.com")));

		me(oldToken).andExpect(status().isUnauthorized());
		me(newToken).andExpect(status().isOk());
		login("r@example.com", "brand-new-pass").andExpect(status().isOk());
		login("r@example.com", "password123").andExpect(status().isUnauthorized());
	}

	@Test
	void fiveWrongAnswersLockResetsForADay() throws Exception {
		register("l@example.com", "password123", this.petQuestion, "Whiskers").andExpect(status().isCreated());
		for (int left = 4; left >= 1; left--) {
			resetPassword("l@example.com", "Rex", "brand-new-pass").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("WRONG_ANSWER"))
				.andExpect(jsonPath("$.attemptsLeft").value(left));
		}
		resetPassword("l@example.com", "Rex", "brand-new-pass").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RESET_LOCKED"))
			.andExpect(jsonPath("$.retryAfterSeconds").value(greaterThan(80_000)))
			.andExpect(header().exists("Retry-After"));
		resetPassword("l@example.com", "Whiskers", "brand-new-pass").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RESET_LOCKED"));

		this.jdbc.update("UPDATE users SET reset_locked_until = now() - interval '1 second'");
		resetPassword("l@example.com", "Whiskers", "brand-new-pass").andExpect(status().isOk());
	}

	@Test
	void unknownEmailsLookLikeRealAccounts() throws Exception {
		String first = question("ghost@example.com").andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String again = question("ghost@example.com").andReturn().getResponse().getContentAsString();
		assertThat(again).as("same decoy every time").isEqualTo(first);
		for (int left = 4; left >= 1; left--) {
			resetPassword("ghost@example.com", "anything", "brand-new-pass")
				.andExpect(jsonPath("$.code").value("WRONG_ANSWER"))
				.andExpect(jsonPath("$.attemptsLeft").value(left));
		}
		resetPassword("ghost@example.com", "anything", "brand-new-pass").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RESET_LOCKED"));
	}

	@Test
	void accountsWithoutAQuestionCantBeReset() throws Exception {
		register("v1@example.com", "password123", null, null).andExpect(status().isCreated());
		question("v1@example.com").andExpect(status().isOk());
		resetPassword("v1@example.com", "", "brand-new-pass").andExpect(status().isBadRequest());
		resetPassword("v1@example.com", "guess", "brand-new-pass").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("WRONG_ANSWER"));
		login("v1@example.com", "password123").andExpect(status().isOk());
	}

	@Test
	void signingInWithThePasswordClearsWrongAnswers() throws Exception {
		register("c@example.com", "password123", this.petQuestion, "Whiskers").andExpect(status().isCreated());
		resetPassword("c@example.com", "Rex", "brand-new-pass").andExpect(jsonPath("$.attemptsLeft").value(4));
		resetPassword("c@example.com", "Rex", "brand-new-pass").andExpect(jsonPath("$.attemptsLeft").value(3));
		login("c@example.com", "password123").andExpect(status().isOk());
		resetPassword("c@example.com", "Rex", "brand-new-pass").andExpect(jsonPath("$.attemptsLeft").value(4));
	}

	@Test
	void settingAQuestionNeedsTheCurrentPassword() throws Exception {
		String token = token(register("s@example.com", "password123", null, null));
		String body = "{\"currentPassword\":\"%s\",\"questionId\":" + this.petQuestion + ",\"answer\":\"Biscuit\"}";
		this.mvc.perform(put("/api/me/security-question").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body.formatted("wrong-password")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
		this.mvc.perform(put("/api/me/security-question").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body.formatted("password123"))).andExpect(status().isNoContent());
		me(token).andExpect(jsonPath("$.hasSecurityQuestion").value(true));
		resetPassword("s@example.com", "biscuit", "brand-new-pass").andExpect(status().isOk());
	}

	@Test
	void resetRequestsAreLimitedPerIp() throws Exception {
		for (int i = 0; i < 20; i++) {
			question("ip@example.com", "203.0.113.7").andExpect(status().isOk());
		}
		question("ip@example.com", "203.0.113.7").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RESET_RATE_LIMITED"));
		// Other networks are unaffected.
		question("ip@example.com", "203.0.113.8").andExpect(status().isOk());
	}

	@Test
	void newPasswordIsValidated() throws Exception {
		register("p@example.com", "password123", this.petQuestion, "Whiskers").andExpect(status().isCreated());
		resetPassword("p@example.com", "Whiskers", "short").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("newPassword")));
	}

	private ResultActions register(String email, String password, Short questionId, String answer)
			throws Exception {
		String extra = (questionId != null) ? ",\"questionId\":" + questionId : "";
		extra += (answer != null) ? ",\"answer\":\"" + answer + "\"" : "";
		return this.mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"" + extra + "}"));
	}

	private ResultActions login(String email, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private ResultActions question(String email) throws Exception {
		return question(email, "127.0.0.1");
	}

	private ResultActions question(String email, String ip) throws Exception {
		return this.mvc.perform(post("/api/auth/password/question").with((request) -> {
			request.setRemoteAddr(ip);
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"));
	}

	private ResultActions resetPassword(String email, String answer, String newPassword) throws Exception {
		return this.mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"answer\":\"" + answer + "\",\"newPassword\":\"" + newPassword
					+ "\"}"));
	}

	private ResultActions me(String token) throws Exception {
		return this.mvc.perform(get("/api/me").header("Authorization", "Bearer " + token));
	}

	private static String token(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.token");
	}

	private static String token(String body) {
		return JsonPath.read(body, "$.token");
	}

}
