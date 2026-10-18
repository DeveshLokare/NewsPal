package com.newspal.common;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated user's id is the JWT subject (see {@code JwtService}).
 */
public final class CurrentUser {

	private CurrentUser() {
	}

	public static long id(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
