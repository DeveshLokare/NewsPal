package com.newspal.auth;

import java.util.Locale;
import java.util.Optional;

import com.newspal.auth.dto.AuthDtos.AuthResponse;
import com.newspal.auth.dto.AuthDtos.LoginRequest;
import com.newspal.auth.dto.AuthDtos.RegisterRequest;
import com.newspal.auth.dto.AuthDtos.UserDto;
import com.newspal.common.ApiException;
import com.newspal.common.ErrorCodes;
import com.newspal.user.User;
import com.newspal.user.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final JwtService jwtService;

	/** Compared against when the email is unknown, so both failure paths take similar time. */
	private final String dummyHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.dummyHash = passwordEncoder.encode("newspal-dummy-password");
	}

	@Transactional
	public AuthResponse register(RegisterRequest request) {
		String email = normalizeEmail(request.email());
		if (this.users.existsByEmail(email)) {
			throw emailTaken();
		}
		User user;
		try {
			user = this.users.saveAndFlush(new User(email, this.passwordEncoder.encode(request.password())));
		}
		catch (DataIntegrityViolationException ex) {
			// Two registrations for the same email raced past the exists check.
			throw emailTaken();
		}
		return toResponse(user);
	}

	@Transactional(readOnly = true)
	public AuthResponse login(LoginRequest request) {
		Optional<User> user = this.users.findByEmail(normalizeEmail(request.email()));
		String hash = user.map(User::getPasswordHash).orElse(this.dummyHash);
		boolean matches = this.passwordEncoder.matches(request.password(), hash);
		if (user.isEmpty() || !matches) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.BAD_CREDENTIALS, "Invalid email or password");
		}
		return toResponse(user.get());
	}

	@Transactional(readOnly = true)
	public UserDto currentUser(long userId) {
		return this.users.findById(userId)
			.map((user) -> new UserDto(user.getId(), user.getEmail()))
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
					"Authentication required"));
	}

	private AuthResponse toResponse(User user) {
		JwtService.IssuedToken token = this.jwtService.issue(user.getId());
		return new AuthResponse(token.value(), token.expiresAt(), new UserDto(user.getId(), user.getEmail()));
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, ErrorCodes.EMAIL_TAKEN, "Email already registered");
	}

}
