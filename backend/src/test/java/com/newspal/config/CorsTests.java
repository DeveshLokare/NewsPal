package com.newspal.config;

import com.newspal.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dev-only CORS setting lets a browser-hosted Flutter app on localhost call the API.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "newspal.cors.allowed-origin-patterns=http://localhost:*")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void allowsLocalhostPreflight() throws Exception {
		this.mvc.perform(options("/api/feed").header("Origin", "http://localhost:51234")
			.header("Access-Control-Request-Method", "GET")
			.header("Access-Control-Request-Headers", "authorization"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:51234"));
	}

	@Test
	void rejectsOtherOrigins() throws Exception {
		this.mvc.perform(options("/api/feed").header("Origin", "https://evil.example")
			.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isForbidden());
	}

}
