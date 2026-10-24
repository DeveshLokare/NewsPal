package com.newspal.auth;

import java.time.Clock;
import java.time.Instant;

import com.newspal.config.JwtProperties;
import com.newspal.config.SecurityConfig;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues HS256 access tokens whose subject is the user id.
 */
@Service
public class JwtService {

	private final JwtEncoder encoder;

	private final JwtProperties properties;

	private final Clock clock;

	public JwtService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	/** Claim holding the account's last password change (epoch millis), if any. */
	public static final String PASSWORD_CHANGED_CLAIM = "pwc";

	public IssuedToken issue(long userId) {
		return issue(userId, null);
	}

	/**
	 * @param passwordChangedAt the account's last password reset, or {@code null} if
	 * never; tokens carrying an older value are rejected (PasswordChangeValidator)
	 */
	public IssuedToken issue(long userId, Instant passwordChangedAt) {
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(this.properties.ttl());
		JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
			.issuer(SecurityConfig.ISSUER)
			.subject(Long.toString(userId))
			.issuedAt(now)
			.expiresAt(expiresAt);
		if (passwordChangedAt != null) {
			builder.claim(PASSWORD_CHANGED_CLAIM, passwordChangedAt.toEpochMilli());
		}
		JwtClaimsSet claims = builder.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = this.encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(token, expiresAt);
	}

	public record IssuedToken(String value, Instant expiresAt) {
	}

}
