package com.newspal.article;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Strips page clutter that providers scrape into the start of a summary: navigation
 * and menus, cookie and audio notices, datelines, photo credits, bylines and a
 * repeated title. It is a heuristic, so it errs on the side of keeping text.
 */
final class SummaryCleaner {

	/** Shorter than this after cleaning is not worth showing. */
	private static final int MIN_CHARS = 20;

	private static final int MAX_PASSES = 12;

	private static final String MONTH = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?"
			+ "|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)";

	private static final String ZONE = "(?:UTC|GMT|BST|IST|PHT|PKT|SGT|HKT|JST|KST|CET|CEST|EET|EEST|WAT|EAT|SAST"
			+ "|AEST|AEDT|ACST|AWST|NZST|NZDT|EST|EDT|CST|CDT|MST|MDT|PST|PDT)(?:[+-]\\d{1,2}(?::\\d{2})?)?";

	/** Separators left behind between clutter pieces. */
	private static final String GAP = "[\\s|/:,.·•–-]*";

	/** "13m ago", "26 seconds agoon", "minutes ago". */
	private static final String RELATIVE_TIME = "(?:\\d+\\s*)?(?:m|mins?|minutes?|h|hrs?|hours?|secs?|seconds?|days?)"
			+ "\\s*ago(?:on)?(?=\\W|\\p{Lu}|$)";

	private static final Pattern RELATIVE = Pattern.compile("^" + RELATIVE_TIME + GAP);

	/** Removed wherever they appear. */
	private static final Pattern ANYWHERE = Pattern
		.compile("\\s*(?:Prefer HTon Google|\\(Version française disponible ici\\.?\\))\\s*");

	/** The whole summary is a notice, not the article. */
	private static final Pattern JUNK_ONLY = Pattern.compile("(?i)cookies must be enabled in your browser"
			+ "|may be using cookies and other technologies|The No\\. 1 source of news for every"
			+ "|Audio reading is not supported");

	/** Clutter removed from the start, one piece per match. */
	private static final List<Pattern> LEADING = Stream.of(
			"Skip to (?:main content|main|content|footer|sections navigation|navigation|form guide|previous scores)",
			"Open accessibility guide", "Advertisement", "Open this photo in gallery",
			"\\*?This content was translated by AI\\.?\\s*View Original",
			// "NEWYou can now listen to Fox News articles!"
			"NEW(?=[A-Z][a-z])", "You can now listen to [^.!?]{1,40}articles!",
			"Listen to (?:this )?article(?:\\s*\\d+\\s*min(?:ute)?s?(?: listen)?)?",
			"Summary(?=[A-Z][a-z])", "Story by(?=[A-Z])", "Key events", "Opening summary", "Preamble",
			"Table of Contents",
			RELATIVE_TIME, "Just now", "Photo by [^/.]{1,40}/\\s*Unsplash",
			// "Home PhonesNews Great hardware…"
			"Home\\s+(?:[A-Z][a-z]+){2,}(?=\\s)",
			// A run of 4+ capitalised words glued together is a menu: "AllNewsSportCultureLifestyle".
			"(?:[A-Z][a-z]+){4,}",
			// 2+ glued capitalised words glued onto the text itself: "LifeWellbeingAn intense…".
			"(?:[A-Z][a-z]+){2,}(?=[A-Z][a-z]*\\s)",
			// A menu of 6+ upper-case words: "NEWS AKTIEN BÖRSE WIRTSCHAFT POLITIK SPORT…".
			"(?:\\p{Lu}{2,}\\s+){5,}\\p{Lu}{2,}(?=\\s)",
			// Bylines: "By LAURA FOX, ASSOCIATE SHOWBUSINESS EDITOR Published…", "By Dipo Olowookere The bulls…"
			"By (?:\\p{Lu}[\\p{L}.'-]+\\s?){1,4}(?:,\\s*[\\p{Lu} ]+)?\\s+(?=Published|Updated)",
			"By (?:\\p{Lu}\\p{Ll}+[-']?\\p{L}* ){2,3}(?=(?:The|A|An|In|On|At|It|This|After|As) )")
		.map((regex) -> Pattern.compile("^(?:" + regex + ")" + GAP))
		.toList();

	private static final Pattern CAPTION_LABEL = Pattern
		.compile("^(?i:photo|foto|picture|image|stock photo)(?i: credit| source| caption)?\\s*[:=,]\\s*");

	private static final Pattern KNOWN_CREDIT = Pattern.compile(
			"^(?:X|Instagram|Getty Images|Reuters|AFP|AP|PTI|ANI|Canva|Shutterstock|Unsplash)(?=\\p{Lu}|\\s)\\s*");

	/** A credit ending in ". " soon after the label: "Dimitri Karastelev/Unsplash. MOST people…". */
	private static final Pattern SHORT_CREDIT = Pattern.compile("^[^.]{1,60}?\\.\\s+(?=\\p{Lu})");

	private static final Pattern DATE = Pattern.compile("^(?:\\d{1,2}(?:st|nd|rd|th)?[-\\s]" + MONTH
			+ "\\.?[-\\s,]+\\d{4}" + "|" + MONTH + "\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4}" + "|" + MONTH
			+ "\\s+\\d{4}" + "|\\d{4}-\\d{2}-\\d{2})" + GAP);

	private static final Pattern TIME = Pattern.compile("^\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp]\\.?[Mm]\\.?)?"
			+ "(?:\\s*(?:" + ZONE + "|\\(UTC[+-]\\d{2}:\\d{2}\\)))?" + GAP);

	/** Words that only count as clutter next to a date or time. */
	private static final Pattern DATE_LABEL = Pattern.compile("^(?:(?:Published|Last Updated|Updated|Posted|Live"
			+ "|News|Articles|on|at)\\b|(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*\\b|" + ZONE + ")" + GAP);

	private SummaryCleaner() {
	}

	/**
	 * @param summary whitespace-collapsed summary text
	 * @param title the article's title, which some sites repeat at the start
	 * @return the cleaned summary, or {@code null} if nothing worth showing is left
	 */
	static String clean(String summary, String title) {
		if (summary == null) {
			return null;
		}
		if (JUNK_ONLY.matcher(summary.substring(0, Math.min(summary.length(), 300))).find()) {
			return null;
		}
		String text = ANYWHERE.matcher(summary).replaceAll(" ").strip();
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			String next = stripTitle(stripDateline(stripCaption(stripLeading(text))), title);
			if (next.equals(text)) {
				break;
			}
			text = next;
		}
		return (text.length() < MIN_CHARS) ? null : text;
	}

	private static String stripLeading(String text) {
		for (Pattern pattern : LEADING) {
			Matcher matcher = pattern.matcher(text);
			if (matcher.find()) {
				return text.substring(matcher.end());
			}
		}
		return text;
	}

	private static String stripCaption(String text) {
		Matcher label = CAPTION_LABEL.matcher(text);
		if (!label.find()) {
			return text;
		}
		String rest = text.substring(label.end());
		for (Pattern credit : List.of(KNOWN_CREDIT, SHORT_CREDIT)) {
			Matcher matcher = credit.matcher(rest);
			if (matcher.find()) {
				return rest.substring(matcher.end());
			}
		}
		return rest;
	}

	/**
	 * Strips a run of dateline pieces ("Published 08:00 24 Oct 2026 GMT+1"), but only if
	 * it holds an actual date or time, so text that merely starts with "Live" or
	 * "Saturday" is kept.
	 */
	private static String stripDateline(String text) {
		String rest = text;
		boolean sawDateOrTime = false;
		while (!rest.isEmpty()) {
			Matcher date = DATE.matcher(rest);
			Matcher time = TIME.matcher(rest);
			Matcher relative = RELATIVE.matcher(rest);
			Matcher label = DATE_LABEL.matcher(rest);
			if (date.find()) {
				rest = rest.substring(date.end());
				sawDateOrTime = true;
			}
			else if (time.find()) {
				rest = rest.substring(time.end());
				sawDateOrTime = true;
			}
			else if (relative.find()) {
				rest = rest.substring(relative.end());
				sawDateOrTime = true;
			}
			else if (label.find() && label.end() > 0) {
				rest = rest.substring(label.end());
			}
			else {
				break;
			}
		}
		return sawDateOrTime ? rest : text;
	}

	/** Strips the title if the summary starts with it, ignoring case and punctuation. */
	private static String stripTitle(String text, String title) {
		if (title == null) {
			return text;
		}
		String wanted = title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
		if (wanted.length() < 15) {
			return text;
		}
		int matched = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = Character.toLowerCase(text.charAt(i));
			if (!Character.isLetterOrDigit(c)) {
				continue;
			}
			if (c != wanted.charAt(matched)) {
				return text;
			}
			if (++matched == wanted.length()) {
				return text.substring(i + 1).replaceFirst("^" + GAP, "");
			}
		}
		return text;
	}

}
