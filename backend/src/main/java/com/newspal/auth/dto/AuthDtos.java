package com.newspal.auth.dto;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies for {@code /api/auth/**} and {@code /api/me}.
 */
public final class AuthDtos {

	private AuthDtos() {
	}

	public record RegisterRequest(
			@NotBlank @Email @Size(max = 254) String email,
			// BCrypt only uses the first 72 bytes of a password.
			@NotBlank @Size(min = 8, max = 72) String password) {

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

	public record UserDto(Long id, String email) {
	}

	public record AuthResponse(String token, Instant expiresAt, UserDto user) {
	}

}
