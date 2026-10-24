package com.newspal.provider.freenews;

import java.time.Instant;

import com.newspal.provider.ProviderArticle;
import com.newspal.provider.ProviderRefPage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class FreeNewsResponseParserTests {

	private final FreeNewsResponseParser parser = new FreeNewsResponseParser(JsonMapper.builder().build());

	/** Shape from the OpenAPI examples (trimmed). */
	@Test
	void parsesNewsList() {
		String body = """
				{
				  "data": [
				    {"uuid": "dcc8e6d4-1464-4baa-9b63-c6f92287c0da", "title": "Billy Idol thrilled",
				     "published_at": "2026-04-14T07:32:47.000Z", "publisher": "Yahoo"},
				    {"uuid": "3305e809", "title": "Liverpool targets comeback",
				     "published_at": "2026-04-14T07:24:37.000Z", "publisher": null},
				    {"uuid": "no-date", "title": "Skipped"},
				    {"title": "No id", "published_at": "2026-04-14T07:00:00.000Z"}
				  ],
				  "meta": {"page_size": 10, "returned": 4, "has_more": true, "order_by": "recent",
				           "filters": {}, "offset": null, "next_offset": null, "next_cursor": "eyJ2IjoxfQ=="}
				}""";
		ProviderRefPage page = this.parser.parseList(body);
		assertThat(page.items()).hasSize(2);
		assertThat(page.items().get(0).providerId()).isEqualTo("dcc8e6d4-1464-4baa-9b63-c6f92287c0da");
		assertThat(page.items().get(0).publishedAt()).isEqualTo(Instant.parse("2026-04-14T07:32:47Z"));
		assertThat(page.items().get(0).publisher()).isEqualTo("Yahoo");
		assertThat(page.items().get(1).publisher()).isNull();
		assertThat(page.nextCursor()).isEqualTo("eyJ2IjoxfQ==");
	}

	@Test
	void noCursorWhenThereAreNoMorePages() {
		ProviderRefPage page = this.parser
			.parseList("{\"data\": [], \"meta\": {\"has_more\": false, \"next_cursor\": \"abc\"}}");
		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void parsesDetails() {
		ProviderArticle article = this.parser.parseDetails(details("\"https://www.yahoo.com/a.html\"",
				"\"Billy Idol has expressed his delight...\"", "\"Full body\""))
			.orElseThrow();
		assertThat(article.providerId()).isEqualTo("u1");
		assertThat(article.url()).isEqualTo("https://www.yahoo.com/a.html");
		assertThat(article.title()).isEqualTo("Billy Idol thrilled");
		assertThat(article.summary()).isEqualTo("Billy Idol has expressed his delight...");
		assertThat(article.imageUrl()).isEqualTo("https://s.yimg.com/img.webp");
		assertThat(article.source()).isEqualTo("Yahoo");
		assertThat(article.publishedAt()).isEqualTo(Instant.parse("2026-04-14T07:32:47Z"));
	}

	@Test
	void summaryFallsBackToTheStartOfTheBody() {
		String longBody = "word ".repeat(1_000);
		ProviderArticle article = this.parser
			.parseDetails(details("\"https://example.com/a\"", "null", "\"" + longBody + "\""))
			.orElseThrow();
		assertThat(article.summary()).startsWith("word word").hasSizeLessThanOrEqualTo(2_000);
	}

	@Test
	void articlesWithoutAnHttpUrlAreSkipped() {
		assertThat(this.parser.parseDetails(details("null", "\"Intro\"", "null"))).isEmpty();
		assertThat(this.parser.parseDetails(details("\"mailto:x@example.com\"", "\"Intro\"", "null"))).isEmpty();
	}

	@Test
	void parsesErrors() {
		FreeNewsResponseParser.Error burst = this.parser
			.parseError("{\"error\": \"Too Many Requests\", \"retry_after_ms\": 724}");
		assertThat(burst.retryAfterMs()).isEqualTo(724);
		assertThat(burst.isQuotaExceeded()).isFalse();
		assertThat(this.parser.parseError("{\"error\": \"Daily quota exceeded\"}").isQuotaExceeded()).isTrue();
		assertThat(this.parser.parseError("not json").message()).isNull();
	}

	private static String details(String url, String incipit, String body) {
		return """
				{"data": {
				  "uuid": "u1", "title": "Billy Idol thrilled", "thumbnail": "https://s.yimg.com/img.webp",
				  "publisher": "Yahoo", "authors": ["Cover Media"], "topics": ["entertainment"],
				  "countries": ["US"], "languages": ["en"], "published_at": "2026-04-14T07:32:47.000Z",
				  "original_url": %s, "incipit": %s, "body": %s
				}}""".formatted(url, incipit, body);
	}

}
