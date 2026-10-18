package com.newspal.provider.webz;

import java.time.Instant;

import com.newspal.provider.ProviderArticle;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WebzResponseParserTests {

	private final WebzResponseParser parser = new WebzResponseParser(JsonMapper.builder().build());

	/** Shape observed from the live /api/news endpoint on 2026-10-18 (trimmed). */
	@Test
	void parsesNewsApiResponse() {
		String body = """
				{
				  "posts": [
				    {
				      "thread": {
				        "uuid": "t1",
				        "url": "https://www.bbc.com/news/tech-1",
				        "site_full": "www.bbc.com",
				        "site": "bbc.com",
				        "site_categories": ["media"],
				        "title": "Thread title",
				        "published": "2026-10-17T08:00:00.000+03:00",
				        "main_image": "https://ichef.bbci.co.uk/img.jpg",
				        "domain_rank": 120
				      },
				      "uuid": "p1",
				      "url": "https://www.bbc.com/news/tech-1",
				      "published": "2026-10-17T08:00:00.000+03:00",
				      "title": "Chips get faster",
				      "text": "The full article body, thousands of characters long...",
				      "summary": "Short summary from webz.io",
				      "language": "english",
				      "categories": ["Science and Technology"],
				      "crawled": "2026-10-17T08:05:00.000+03:00"
				    }
				  ],
				  "total_results": 71711,
				  "more_results_available": 71701,
				  "balance": 991,
				  "cost": 1,
				  "warnings": null,
				  "next": "/api/news?token=x&ts=1&from=10"
				}""";
		WebzResponseParser.Result result = this.parser.parse(body);
		assertThat(result.articles()).containsExactly(new ProviderArticle("https://www.bbc.com/news/tech-1",
				"Chips get faster", "Short summary from webz.io", "https://ichef.bbci.co.uk/img.jpg", "bbc.com",
				Instant.parse("2026-10-17T05:00:00Z")));
		assertThat(result.next()).isEqualTo("/api/news?token=x&ts=1&from=10");
		assertThat(result.remaining()).isEqualTo(991.0);
	}

	@Test
	void fallsBackToTextWhenNoSummary() {
		String body = """
				{ "posts": [ { "url": "https://example.com/x", "title": "T", "text": "Body text",
				  "published": "2026-10-17T08:00:00Z" } ], "requests_left": 987 }""";
		WebzResponseParser.Result result = this.parser.parse(body);
		assertThat(result.articles().get(0).summary()).isEqualTo("Body text");
		assertThat(result.remaining()).isEqualTo(987.0);
	}

	@Test
	void fallsBackToThreadFieldsAndCamelCasePaging() {
		String body = """
				{
				  "posts": [
				    {
				      "thread": {
				        "url": "https://example.com/a",
				        "title": "From thread",
				        "published": "2026-10-17T08:00:00Z",
				        "site_full": "www.example.com"
				      }
				    }
				  ],
				  "requestsLeft": 5
				}""";
		WebzResponseParser.Result result = this.parser.parse(body);
		ProviderArticle article = result.articles().get(0);
		assertThat(article.url()).isEqualTo("https://example.com/a");
		assertThat(article.title()).isEqualTo("From thread");
		assertThat(article.source()).isEqualTo("www.example.com");
		assertThat(article.summary()).isNull();
		assertThat(article.imageUrl()).isNull();
		assertThat(result.remaining()).isEqualTo(5.0);
	}

	@Test
	void skipsPostsMissingRequiredFields() {
		String body = """
				{
				  "posts": [
				    { "title": "No url", "published": "2026-10-17T08:00:00Z" },
				    { "url": "https://example.com/b", "published": "2026-10-17T08:00:00Z" },
				    { "url": "https://example.com/c", "title": "Bad date", "published": "yesterday" },
				    { "url": "https://example.com/d", "title": "  ", "published": "2026-10-17T08:00:00Z" }
				  ]
				}""";
		assertThat(this.parser.parse(body).articles()).isEmpty();
	}

	@Test
	void emptyResponseHasNoArticles() {
		WebzResponseParser.Result result = this.parser.parse("{\"posts\": []}");
		assertThat(result.articles()).isEmpty();
		assertThat(result.next()).isNull();
		assertThat(result.remaining()).isNull();
	}

}
