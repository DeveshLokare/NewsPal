package com.newspal.common;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

import org.springframework.http.HttpStatus;

/**
 * Opaque keyset-pagination cursors: base64url of {@code <instant>|<id>} (SPEC §7.4).
 */
public final class CursorCodec {

	private CursorCodec() {
	}

	public static String encode(Instant at, long id) {
		String raw = at.toString() + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Decodes a cursor, or returns {@code null} for a {@code null}/blank one.
	 * @throws ApiException 400 {@code BAD_CURSOR} if it cannot be decoded
	 */
	public static Position decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			int bar = raw.indexOf('|');
			if (bar < 0) {
				throw badCursor();
			}
			return new Position(Instant.parse(raw.substring(0, bar)), Long.parseLong(raw.substring(bar + 1)));
		}
		catch (IllegalArgumentException | DateTimeException ex) {
			throw badCursor();
		}
	}

	public static ApiException badCursor() {
		return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_CURSOR, "Cursor is invalid or expired");
	}

	public record Position(Instant at, long id) {
	}

}
