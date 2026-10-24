package com.newspal.search;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits what the user typed into a Postgres {@code websearch_to_tsquery} part and an
 * optional prefix for the last word, so results show up while a word is still being
 * typed ("tesl" finds Tesla). The prefix is only used for a plain last word: not when
 * it's quoted, negated ({@code -word}) or follows "or".
 * @param websearch text for {@code websearch_to_tsquery}; may be empty
 * @param prefix letters and digits only, safe to use in {@code to_tsquery} as
 * {@code prefix:*}; {@code null} if there is none
 */
record SearchQuery(String websearch, String prefix) {

	private static final Pattern LAST_WORD = Pattern.compile("^(.*?)(?<![-\"\\p{L}\\p{N}])([\\p{L}\\p{N}]{2,})$",
			Pattern.DOTALL);

	private static final Pattern ENDS_WITH_OR = Pattern.compile("(?i)(^|\\s)or\\s*$");

	static SearchQuery parse(String text) {
		String trimmed = text.strip();
		Matcher matcher = LAST_WORD.matcher(trimmed);
		boolean insideQuotes = trimmed.chars().filter((c) -> c == '"').count() % 2 == 1;
		if (!matcher.matches() || insideQuotes || ENDS_WITH_OR.matcher(matcher.group(1)).find()) {
			return new SearchQuery(trimmed, null);
		}
		return new SearchQuery(matcher.group(1).strip(), matcher.group(2).toLowerCase(Locale.ROOT));
	}

}
