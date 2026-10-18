package com.newspal.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApi(ApiException ex) {
		return ResponseEntity.status(ex.status()).body(ApiError.of(ex.code(), ex.getMessage()));
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
