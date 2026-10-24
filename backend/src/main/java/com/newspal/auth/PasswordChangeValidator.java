package com.newspal.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Rejects tokens issued before the user's last password reset, which signs out their
 * other devices (SPEC-v2 §7.4, US-16). Each token carries the reset time it was issued
 * after ({@link JwtService#PASSWORD_CHANGED_CLAIM}); comparing that instead of
 * {@code iat}, which has whole-second precision, also covers a token issued in the
 * same second as the reset. Lookups are cached for a minute; a reset on this instance
 * clears its user's entry at once.
 */
@Component
public class PasswordChangeValidator implements OAuth2TokenValidator<Jwt> {

	private static final OAuth2Error SIGNED_OUT = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
			"Token was issued before the password was changed", null);

	private final UserSecurity userSecurity;

	private final Cache<Long, Optional<Instant>> changedAt = Caffeine.newBuilder()
		.expireAfterWrite(Duration.ofMinutes(1))
		.maximumSize(10_000)
		.build();

	public PasswordChangeValidator(UserSecurity userSecurity) {
		this.userSecurity = userSecurity;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		long userId;
		try {
			userId = Long.parseLong(jwt.getSubject());
		}
		catch (NumberFormatException ex) {
			return OAuth2TokenValidatorResult.failure(SIGNED_OUT);
		}
		Optional<Instant> changed = this.changedAt.get(userId, this.userSecurity::passwordChangedAt);
		if (changed.isEmpty()) {
			return OAuth2TokenValidatorResult.success();
		}
		Object claim = jwt.getClaim(JwtService.PASSWORD_CHANGED_CLAIM);
		boolean current = claim instanceof Number millis && millis.longValue() >= changed.get().toEpochMilli();
		return current ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(SIGNED_OUT);
	}

	/** Drops the cached change time after a reset. */
	void forget(long userId) {
		this.changedAt.invalidate(userId);
	}

}
