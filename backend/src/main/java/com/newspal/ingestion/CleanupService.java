package com.newspal.ingestion;

import java.time.Clock;

import com.newspal.article.ArticleStore;
import com.newspal.config.NewsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention for stored articles (SPEC §4.4). Archived articles live in their own
 * table and are never touched.
 */
@Service
public class CleanupService {

	private static final Logger logger = LoggerFactory.getLogger(CleanupService.class);

	private final ArticleStore articles;

	private final NewsProperties properties;

	private final Clock clock;

	public CleanupService(ArticleStore articles, NewsProperties properties, Clock clock) {
		this.articles = articles;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public void cleanUp() {
		int expired = this.articles.deleteOlderThan(this.clock.instant().minus(this.properties.retention()));
		int trimmed = this.articles.trimPerCategory(this.properties.maxPerCategory());
		if (expired + trimmed > 0) {
			logger.info("Cleanup removed {} expired and {} over-limit articles", expired, trimmed);
		}
	}

}
