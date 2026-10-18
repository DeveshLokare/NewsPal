package com.newspal.support;

import com.jayway.jsonpath.JsonPath;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registers users through the API and returns their bearer header.
 */
public final class TestUsers {

	private TestUsers() {
	}

	/** Registers {@code email} and returns {@code "Bearer <token>"}. */
	public static String register(MockMvc mvc, String email) throws Exception {
		String body = mvc
			.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.token");
	}

	/** Sets the user's followed categories. */
	public static void follow(MockMvc mvc, String bearer, String... slugs) throws Exception {
		String json = "[\"" + String.join("\",\"", slugs) + "\"]";
		mvc.perform(put("/api/me/categories").header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)).andExpect(status().isNoContent());
	}

}
