package com.newspal.auth.dto;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies for {@code /api/auth/**} and {@code /api/me}.
 */
public final class AuthDtos {

	private AuthDtos() {
	}

	/**
	 * {@code questionId} and {@code answer} go together. They are optional for now so
	 * that app versions from before security questions can still register (SPEC-v2
	 * §7.2).
	 */
	public record RegisterRequest(
			@NotBlank @Email @Size(max = 254) String email,
			// BCrypt only uses the first 72 bytes of a password.
			@NotBlank @Size(min = 8, max = 72) String password,
			Short questionId,
			@Size(max = 100) String answer) {

		public RegisterRequest {
			email = trim(email);
		}

	}

	public record LoginRequest(@NotBlank String email, @NotBlank String password) {

		public LoginRequest {
			email = trim(email);
		}

	}

	/** Mobile keyboards often add a trailing space after autocomplete. */
	private static String trim(String email) {
		return (email != null) ? email.trim() : null;
	}

	public record UserDto(Long id, String email, boolean hasSecurityQuestion) {
	}

	public record AuthResponse(String token, Instant expiresAt, UserDto user) {
	}

	public record SecurityQuestionDto(short id, String text) {
	}

	public record SetSecurityQuestionRequest(@NotBlank String currentPassword, @NotNull Short questionId,
			@NotBlank @Size(max = 100) String answer) {
	}

	public record ResetQuestionRequest(@NotBlank @Email @Size(max = 254) String email) {

		public ResetQuestionRequest {
			email = trim(email);
		}

	}

	/** The account's question, or a stable decoy for unknown emails (SPEC-v2 §7.4). */
	public record ResetQuestionResponse(short questionId, String text) {
	}

	public record ResetPasswordRequest(@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(max = 100) String answer, @NotBlank @Size(min = 8, max = 72) String newPassword) {

		public ResetPasswordRequest {
			email = trim(email);
		}

	}

}
