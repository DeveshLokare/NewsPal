package com.newspal.provider.webz;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.newspal.provider.ProviderArticle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Turns a webz.io response body into {@link ProviderArticle}s. webz.io documents
 * both snake_case and camelCase field names across its APIs, so both are accepted,
 * and posts missing a URL, title or publish date are skipped.
 */
@Component
public class WebzResponseParser {

	private final JsonMapper jsonMapper;

	public WebzResponseParser(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public Result parse(String body) {
		JsonNode root = this.jsonMapper.readTree(body);
		List<ProviderArticle> articles = new ArrayList<>();
		for (JsonNode post : root.path("posts")) {
			ProviderArticle article = toArticle(post);
			if (article != null) {
				articles.add(article);
			}
		}
		String next = text(root, "next");
		// /api/news reports "balance"; other webz.io APIs use requests_left / requestsLeft.
		Double remaining = number(root, "balance", "requests_left", "requestsLeft");
		return new Result(articles, next, remaining);
	}

	private static ProviderArticle toArticle(JsonNode post) {
		JsonNode thread = post.path("thread");
		String url = firstNonBlank(text(post, "url"), text(thread, "url"));
		String title = firstNonBlank(text(post, "title"), text(thread, "title"));
		Instant publishedAt = instant(firstNonBlank(text(post, "published"), text(thread, "published")));
		if (url == null || title == null || publishedAt == null) {
			return null;
		}
		// Prefer webz.io's own summary; "text" is the full article body.
		String summary = firstNonBlank(text(post, "summary"), text(post, "text"));
		return new ProviderArticle(url, title, summary, text(thread, "main_image"),
				firstNonBlank(text(thread, "site"), text(thread, "site_full")), publishedAt);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.isValueNode() || value.isNull()) {
			return null;
		}
		String text = value.asString().trim();
		return text.isEmpty() ? null : text;
	}

	private static Double number(JsonNode node, String... fields) {
		for (String field : fields) {
			JsonNode value = node.path(field);
			if (value.isNumber()) {
				return value.asDouble();
			}
		}
		return null;
	}

	private static String firstNonBlank(String a, String b) {
		return (a != null) ? a : b;
	}

	private static Instant instant(String value) {
		if (value == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).toInstant();
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	public record Result(List<ProviderArticle> articles, String next, Double remaining) {
	}

}
