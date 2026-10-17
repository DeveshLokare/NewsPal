package com.newspal.provider.webz;

import java.time.Instant;

import com.newspal.provider.ProviderArticle;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WebzResponseParserTests {

	private final WebzResponseParser parser = new WebzResponseParser(JsonMapper.builder().build());

	@Test
	void parsesPostsAndPagingFields() {
		String body = """
				{
				  "posts": [
				    {
				      "thread": {
				        "url": "https://www.bbc.com/news/tech-1",
				        "site": "bbc.com",
				        "main_image": "https://ichef.bbci.co.uk/img.jpg",
				        "title": "Thread title"
				      },
				      "url": "https://www.bbc.com/news/tech-1",
				      "title": "Chips get faster",
				      "text": "Snippet text",
				      "published": "2026-10-17T08:00:00.000+03:00",
				      "language": "english"
				    }
				  ],
				  "next": "/newsApiLite?token=x&ts=1&from=10",
				  "requests_left": 987
				}""";
		WebzResponseParser.Result result = this.parser.parse(body);
		assertThat(result.articles()).containsExactly(new ProviderArticle("https://www.bbc.com/news/tech-1",
				"Chips get faster", "Snippet text", "https://ichef.bbci.co.uk/img.jpg", "bbc.com",
				Instant.parse("2026-10-17T05:00:00Z")));
		assertThat(result.next()).isEqualTo("/newsApiLite?token=x&ts=1&from=10");
		assertThat(result.requestsLeft()).isEqualTo(987);
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
		assertThat(result.requestsLeft()).isEqualTo(5);
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
		assertThat(result.requestsLeft()).isNull();
	}

}
