package com.newspal.common;

/**
 * Error codes from SPEC §7.1 and SPEC-v2 §9.
 */
public final class ErrorCodes {

	public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

	public static final String BAD_CURSOR = "BAD_CURSOR";

	public static final String UNAUTHORIZED = "UNAUTHORIZED";

	public static final String BAD_CREDENTIALS = "BAD_CREDENTIALS";

	public static final String NOT_FOUND = "NOT_FOUND";

	public static final String EMAIL_TAKEN = "EMAIL_TAKEN";

	/** Security answer didn't match; the body has {@code attemptsLeft} (SPEC-v2 §7.4). */
	public static final String WRONG_ANSWER = "WRONG_ANSWER";

	/** Current password didn't match when changing the security question. */
	public static final String WRONG_PASSWORD = "WRONG_PASSWORD";

	/** Too many wrong answers; the body has {@code retryAfterSeconds}. */
	public static final String RESET_LOCKED = "RESET_LOCKED";

	/** Too many reset requests from one IP. */
	public static final String RESET_RATE_LIMITED = "RESET_RATE_LIMITED";

	/** Too many searches in a minute (SPEC-v2 §8.1). */
	public static final String SEARCH_RATE_LIMITED = "SEARCH_RATE_LIMITED";

	public static final String OLDER_RATE_LIMITED = "OLDER_RATE_LIMITED";

	public static final String QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED";

	public static final String PROVIDER_ERROR = "PROVIDER_ERROR";

	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	private ErrorCodes() {
	}

}
