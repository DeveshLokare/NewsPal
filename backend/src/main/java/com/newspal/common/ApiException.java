package com.newspal.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Thrown by services to produce a specific HTTP status and error code.
 * {@code details} are extra top-level fields in the error body, such as
 * {@code attemptsLeft}.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final Map<String, Object> details;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, Map.of());
	}

	public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
		super(message);
		this.status = status;
		this.code = code;
		this.details = Map.copyOf(details);
	}

	public HttpStatus status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

	public Map<String, Object> details() {
		return this.details;
	}

}
