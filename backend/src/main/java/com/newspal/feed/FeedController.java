package com.newspal.feed;

import com.newspal.common.CurrentUser;
import com.newspal.feed.dto.FeedDtos.FeedPage;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feed")
public class FeedController {

	private final FeedService feedService;

	private final OlderNewsService olderNews;

	public FeedController(FeedService feedService, OlderNewsService olderNews) {
		this.feedService = feedService;
		this.olderNews = olderNews;
	}

	@GetMapping
	public FeedPage feed(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String category,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
		return this.feedService.page(CurrentUser.id(jwt), category, cursor, size);
	}

	@GetMapping("/older")
	public FeedPage older(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String category,
			@RequestParam(required = false) String cursor) {
		return this.olderNews.page(CurrentUser.id(jwt), category, cursor);
	}

}
