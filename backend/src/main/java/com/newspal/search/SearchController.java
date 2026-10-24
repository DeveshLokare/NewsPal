package com.newspal.search;

import com.newspal.common.CurrentUser;
import com.newspal.feed.dto.FeedDtos.FeedPage;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

	private final SearchService search;

	public SearchController(SearchService search) {
		this.search = search;
	}

	/** Search the last 7 days of stored news (SPEC-v2 §8.1); same shape as the feed. */
	@GetMapping("/api/search")
	public FeedPage search(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String q,
			@RequestParam(required = false) String category, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int size) {
		return this.search.search(CurrentUser.id(jwt), q, category, cursor, size);
	}

}
