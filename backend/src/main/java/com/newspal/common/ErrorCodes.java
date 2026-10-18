package com.newspal.common;

/**
 * Error codes from SPEC §7.1.
 */
public final class ErrorCodes {

	public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

	public static final String BAD_CURSOR = "BAD_CURSOR";

	public static final String UNAUTHORIZED = "UNAUTHORIZED";

	public static final String BAD_CREDENTIALS = "BAD_CREDENTIALS";

	public static final String NOT_FOUND = "NOT_FOUND";

	public static final String EMAIL_TAKEN = "EMAIL_TAKEN";

	public static final String OLDER_RATE_LIMITED = "OLDER_RATE_LIMITED";

	public static final String QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED";

	public static final String PROVIDER_ERROR = "PROVIDER_ERROR";

	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	private ErrorCodes() {
	}

}
