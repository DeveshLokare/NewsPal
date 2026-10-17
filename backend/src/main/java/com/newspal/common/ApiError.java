package com.newspal.common;

import java.util.List;

/**
 * The single error shape returned by every endpoint (SPEC §7.1).
 */
public record ApiError(String code, String message, List<FieldError> fieldErrors) {

	public record FieldError(String field, String message) {
	}

	public static ApiError of(String code, String message) {
		return new ApiError(code, message, List.of());
	}

}
