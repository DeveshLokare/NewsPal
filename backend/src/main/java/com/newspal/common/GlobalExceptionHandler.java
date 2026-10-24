package com.newspal.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** An {@link ApiException} detail that is also sent as a {@code Retry-After} header. */
	public static final String RETRY_AFTER_SECONDS = "retryAfterSeconds";

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApi(ApiException ex) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status());
		if (ex.details().get(RETRY_AFTER_SECONDS) instanceof Number seconds) {
			response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds.longValue()));
		}
		ApiError error = ApiError.of(ex.code(), ex.getMessage());
		if (ex.details().isEmpty()) {
			return response.body(error);
		}
		// Same shape as ApiError, plus the extra fields.
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", error.code());
		body.put("message", error.message());
		body.put("fieldErrors", error.fieldErrors());
		body.putAll(ex.details());
		return response.body(body);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		List<ApiError.FieldError> fieldErrors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map((error) -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
			.toList();
		return ResponseEntity.badRequest()
			.body(new ApiError(ErrorCodes.VALIDATION_FAILED, "Request is invalid", fieldErrors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest()
			.body(ApiError.of(ErrorCodes.VALIDATION_FAILED, "Request body is missing or malformed"));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return ResponseEntity.badRequest()
			.body(new ApiError(ErrorCodes.VALIDATION_FAILED, "Request is invalid",
					List.of(new ApiError.FieldError(ex.getName(), "has an invalid value"))));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ErrorCodes.NOT_FOUND, "Not found"));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
			.body(ApiError.of(ErrorCodes.NOT_FOUND, "Method not allowed"));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception ex) {
		logger.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ApiError.of(ErrorCodes.INTERNAL_ERROR, "Something went wrong"));
	}

}
