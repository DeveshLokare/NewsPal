package com.newspal.auth;

import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes and checks security answers (SPEC-v2 §7.4). Answers are normalised first, so
 * "  Mr. Whiskers " matches "mr whiskers", and stored only as a BCrypt hash.
 */
@Component
public class SecurityAnswers {

	private final PasswordEncoder encoder;

	/** Checked against when there is no real answer, so both paths take similar time. */
	private final String dummyHash;

	public SecurityAnswers(PasswordEncoder encoder) {
		this.encoder = encoder;
		this.dummyHash = encoder.encode("newspal-dummy-answer");
	}

	/** Lowercase, punctuation and symbols removed, runs of whitespace collapsed. */
	public static String normalize(String answer) {
		if (answer == null) {
			return "";
		}
		return answer.toLowerCase(Locale.ROOT)
			.replaceAll("[\\p{P}\\p{S}]", "")
			.replaceAll("\\s+", " ")
			.strip();
	}

	public String hash(String answer) {
		return this.encoder.encode(normalize(answer));
	}

	/** @param hash the stored hash, or {@code null} to only spend the time of a check */
	public boolean matches(String answer, String hash) {
		boolean matches = this.encoder.matches(normalize(answer), (hash != null) ? hash : this.dummyHash);
		return hash != null && matches;
	}

}
