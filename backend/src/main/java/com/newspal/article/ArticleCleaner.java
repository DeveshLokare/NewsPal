package com.newspal.article;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.newspal.common.UrlUtils;
import com.newspal.provider.ProviderArticle;

/**
 * Cleans provider articles before they are stored or returned, so stored feed items
 * and live older-news items look the same.
 */
public final class ArticleCleaner {

	public static final int TITLE_MAX = 1000;

	public static final int SOURCE_MAX = 200;

	private static final Pattern ENTITY = Pattern.compile("&(#\\d{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");

	private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(Map.entry("amp", "&"),
			Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("lt", "<"), Map.entry("gt", ">"),
			Map.entry("nbsp", " "), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"),
			Map.entry("rdquo", "”"), Map.entry("ldquo", "“"), Map.entry("ndash", "–"),
			Map.entry("mdash", "—"), Map.entry("hellip", "…"));

	private ArticleCleaner() {
	}

	/**
	 * Returns a cleaned copy, or {@code null} if the post should be dropped: no
	 * http(s) URL, or no text at all (videos, social posts and listings). A summary
	 * that turns out to be only page clutter (a cookie notice, a menu) becomes
	 * {@code null}, but the article is kept.
	 */
	public static ProviderArticle clean(ProviderArticle article, int summaryMaxChars) {
		String rawSummary = collapseWhitespace(decodeEntities(article.summary()));
		if (!UrlUtils.isHttpUrl(article.url()) || rawSummary == null) {
			return null;
		}
		String title = collapseWhitespace(decodeEntities(article.title()));
		if (title == null) {
			title = article.title();
		}
		String summary = SummaryCleaner.clean(rawSummary, title);
		String image = (article.imageUrl() != null && UrlUtils.isHttpUrl(article.imageUrl())) ? article.imageUrl()
				: null;
		return new ProviderArticle(article.providerId(), article.url().trim(), truncate(title, TITLE_MAX),
				truncate(summary, summaryMaxChars), image, truncate(article.source(), SOURCE_MAX),
				article.publishedAt());
	}

	/** Some providers leave HTML entities in their text ({@code &rsquo;}, {@code &#39;}). */
	static String decodeEntities(String text) {
		if (text == null || text.indexOf('&') < 0) {
			return text;
		}
		Matcher matcher = ENTITY.matcher(text);
		StringBuilder decoded = new StringBuilder();
		while (matcher.find()) {
			String replacement = decodeEntity(matcher.group(1));
			matcher.appendReplacement(decoded, Matcher.quoteReplacement((replacement != null) ? replacement
					: matcher.group()));
		}
		matcher.appendTail(decoded);
		return decoded.toString();
	}

	private static String decodeEntity(String name) {
		if (name.startsWith("#")) {
			try {
				int codePoint = (name.charAt(1) == 'x' || name.charAt(1) == 'X')
						? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
				return Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : null;
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return NAMED_ENTITIES.get(name);
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
