package com.newspal.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * URL normalization and hashing used to de-duplicate articles (SPEC §4.3).
 */
public final class UrlUtils {

	private static final Set<String> TRACKING_PARAMS = Set.of("fbclid", "gclid", "mc_cid", "mc_eid");

	private UrlUtils() {
	}

	/**
	 * Lowercases scheme and host, drops the fragment, tracking parameters
	 * ({@code utm_*}, {@code fbclid}, ...) and a trailing slash.
	 */
	public static String normalize(String url) {
		String trimmed = url.trim();
		URI uri;
		try {
			uri = new URI(trimmed);
		}
		catch (URISyntaxException ex) {
			return trimmed;
		}
		if (uri.getScheme() == null || uri.getHost() == null) {
			return trimmed;
		}
		StringBuilder result = new StringBuilder();
		result.append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
		if (uri.getPort() != -1) {
			result.append(':').append(uri.getPort());
		}
		String path = uri.getRawPath();
		if (path != null && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		if (path != null) {
			result.append(path);
		}
		String query = stripTracking(uri.getRawQuery());
		if (!query.isEmpty()) {
			result.append('?').append(query);
		}
		return result.toString();
	}

	/** SHA-256 (hex) of the normalized URL; matches the {@code url_hash CHAR(64)} column. */
	public static String hash(String url) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalize(url).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static boolean isHttpUrl(String url) {
		try {
			URI uri = new URI(url.trim());
			String scheme = uri.getScheme();
			return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

	private static String stripTracking(String rawQuery) {
		if (rawQuery == null || rawQuery.isEmpty()) {
			return "";
		}
		StringJoiner kept = new StringJoiner("&");
		for (String pair : rawQuery.split("&")) {
			String name = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
			if (!pair.isEmpty() && !name.startsWith("utm_") && !TRACKING_PARAMS.contains(name)) {
				kept.add(pair);
			}
		}
		return kept.toString();
	}

}
