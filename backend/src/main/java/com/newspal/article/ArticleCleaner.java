package com.newspal.article;

import com.newspal.common.UrlUtils;
import com.newspal.provider.ProviderArticle;

/**
 * Cleans provider articles before they are stored or returned, so stored feed items
 * and live older-news items look the same.
 */
public final class ArticleCleaner {

	public static final int TITLE_MAX = 1000;

	public static final int SOURCE_MAX = 200;

	private ArticleCleaner() {
	}

	/**
	 * Returns a cleaned copy, or {@code null} if the post should be dropped: no
	 * http(s) URL, or no text at all (videos, social posts and listings).
	 */
	public static ProviderArticle clean(ProviderArticle article, int summaryMaxChars) {
		String summary = collapseWhitespace(article.summary());
		if (!UrlUtils.isHttpUrl(article.url()) || summary == null) {
			return null;
		}
		String image = (article.imageUrl() != null && UrlUtils.isHttpUrl(article.imageUrl())) ? article.imageUrl()
				: null;
		return new ProviderArticle(article.url().trim(), truncate(article.title(), TITLE_MAX),
				truncate(summary, summaryMaxChars), image, truncate(article.source(), SOURCE_MAX),
				article.publishedAt());
	}

	/** Snippets often contain line breaks and runs of spaces; flatten them for display. */
	public static String collapseWhitespace(String text) {
		if (text == null) {
			return null;
		}
		String collapsed = text.replaceAll("\\s+", " ").strip();
		return collapsed.isEmpty() ? null : collapsed;
	}

	/**
	 * Shortens text to at most {@code max} characters, ending with an ellipsis at a
	 * word boundary when cut.
	 */
	public static String truncate(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		String cut = text.substring(0, max - 1);
		boolean endsOnWord = Character.isWhitespace(text.charAt(max - 1));
		int space = cut.lastIndexOf(' ');
		if (!endsOnWord && space > max / 2) {
			cut = cut.substring(0, space);
		}
		return cut.stripTrailing() + "…";
	}

}
