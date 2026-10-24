package com.newspal.provider.freenews;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.newspal.common.UrlUtils;
import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderRef;
import com.newspal.provider.ProviderRefPage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Turns Free News API response bodies into provider records (SPEC-v2 §4.4). Items
 * missing an id, title or publish date are skipped, and so are articles without an
 * http(s) URL, since the app opens every article by URL.
 */
@Component
public class FreeNewsResponseParser {

	/** Enough body text for a summary; the cleaner trims it further. */
	private static final int BODY_FALLBACK_CHARS = 2_000;

	private final JsonMapper jsonMapper;

	public FreeNewsResponseParser(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/** Parses a {@code /news} response. */
	public ProviderRefPage parseList(String body) {
		JsonNode root = this.jsonMapper.readTree(body);
		List<ProviderRef> refs = new ArrayList<>();
		for (JsonNode item : root.path("data")) {
			String id = text(item, "uuid");
			String title = text(item, "title");
			Instant publishedAt = instant(text(item, "published_at"));
			if (id != null && title != null && publishedAt != null) {
				refs.add(new ProviderRef(id, title, publishedAt, text(item, "publisher")));
			}
		}
		JsonNode meta = root.path("meta");
		String next = meta.path("has_more").asBoolean(false) ? text(meta, "next_cursor") : null;
		return new ProviderRefPage(refs, next);
	}

	/** Parses a {@code /details} response; empty if the article can't be shown. */
	public Optional<ProviderArticle> parseDetails(String body) {
		JsonNode data = this.jsonMapper.readTree(body).path("data");
		String id = text(data, "uuid");
		String url = text(data, "original_url");
		String title = text(data, "title");
		Instant publishedAt = instant(text(data, "published_at"));
		if (id == null || url == null || !UrlUtils.isHttpUrl(url) || title == null || publishedAt == null) {
			return Optional.empty();
		}
		String summary = text(data, "incipit");
		if (summary == null) {
			String fullBody = text(data, "body");
			summary = (fullBody != null && fullBody.length() > BODY_FALLBACK_CHARS)
					? fullBody.substring(0, BODY_FALLBACK_CHARS) : fullBody;
		}
		return Optional.of(new ProviderArticle(id, url, title, summary, text(data, "thumbnail"),
				text(data, "publisher"), publishedAt));
	}

	/** Parses an error body: {@code {"error": "...", "retry_after_ms": 724}}. */
	public Error parseError(String body) {
		try {
			JsonNode root = this.jsonMapper.readTree(body);
			JsonNode retry = root.path("retry_after_ms");
			return new Error(text(root, "error"), retry.isNumber() ? retry.asLong() : null);
		}
		catch (RuntimeException ex) {
			return new Error(null, null);
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.isValueNode() || value.isNull()) {
			return null;
		}
		String text = value.asString().trim();
		return text.isEmpty() ? null : text;
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

	/**
	 * @param message the API's error message, if any
	 * @param retryAfterMs how long to wait before retrying, if given
	 */
	public record Error(String message, Long retryAfterMs) {

		/** A daily or monthly quota is used up, as opposed to a short burst limit. */
		public boolean isQuotaExceeded() {
			return this.message != null && this.message.toLowerCase(Locale.ROOT).contains("quota");
		}

	}

}
