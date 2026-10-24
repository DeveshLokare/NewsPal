package com.newspal.auth;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.newspal.auth.dto.AuthDtos.AuthResponse;
import com.newspal.auth.dto.AuthDtos.LoginRequest;
import com.newspal.auth.dto.AuthDtos.RegisterRequest;
import com.newspal.auth.dto.AuthDtos.SecurityQuestionDto;
import com.newspal.auth.dto.AuthDtos.SetSecurityQuestionRequest;
import com.newspal.auth.dto.AuthDtos.UserDto;
import com.newspal.common.ApiError;
import com.newspal.common.ApiException;
import com.newspal.common.ErrorCodes;
import com.newspal.config.ResetProperties;
import com.newspal.user.User;
import com.newspal.user.UserRepository;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(ResetProperties.class)
public class AuthService {

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final JwtService jwtService;

	private final UserSecurity userSecurity;

	private final SecurityQuestions questions;

	private final SecurityAnswers answers;

	private final ResetProperties resetProperties;

	/** Compared against when the email is unknown, so both failure paths take similar time. */
	private final String dummyHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
			UserSecurity userSecurity, SecurityQuestions questions, SecurityAnswers answers,
			ResetProperties resetProperties) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.userSecurity = userSecurity;
		this.questions = questions;
		this.answers = answers;
		this.resetProperties = resetProperties;
		this.dummyHash = passwordEncoder.encode("newspal-dummy-password");
	}

	@Transactional
	public AuthResponse register(RegisterRequest request) {
		String email = normalizeEmail(request.email());
		boolean withQuestion = request.questionId() != null || request.answer() != null;
		if (withQuestion) {
			validateQuestion(request.questionId(), request.answer());
		}
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
		if (withQuestion) {
			this.userSecurity.setQuestion(user.getId(), request.questionId(), this.answers.hash(request.answer()));
		}
		return toResponse(user, withQuestion);
	}

	@Transactional
	public AuthResponse login(LoginRequest request) {
		Optional<User> user = this.users.findByEmail(normalizeEmail(request.email()));
		String hash = user.map(User::getPasswordHash).orElse(this.dummyHash);
		boolean matches = this.passwordEncoder.matches(request.password(), hash);
		if (user.isEmpty() || !matches) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.BAD_CREDENTIALS, "Invalid email or password");
		}
		long userId = user.get().getId();
		// Someone who knows the password needn't be slowed down by an attacker's guesses.
		this.userSecurity.clearWrongAnswers(userId);
		return toResponse(user.get(), hasQuestion(userId));
	}

	@Transactional(readOnly = true)
	public UserDto currentUser(long userId) {
		return this.users.findById(userId)
			.map((user) -> new UserDto(user.getId(), user.getEmail(), hasQuestion(userId)))
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
					"Authentication required"));
	}

	public List<SecurityQuestionDto> securityQuestions() {
		return this.questions.findAll().stream().map((q) -> new SecurityQuestionDto(q.id(), q.text())).toList();
	}

	/**
	 * Sets or changes the user's security question. Needs the current password, so a
	 * borrowed, unlocked phone can't be used to plant an answer. A wrong password is a
	 * {@code 400}, not a {@code 401}: the app signs out on any {@code 401}.
	 */
	@Transactional
	public void setSecurityQuestion(long userId, SetSecurityQuestionRequest request) {
		User user = this.users.findById(userId)
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
					"Authentication required"));
		validateQuestion(request.questionId(), request.answer());
		if (!this.passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.WRONG_PASSWORD, "Your password isn't right");
		}
		this.userSecurity.setQuestion(userId, request.questionId(), this.answers.hash(request.answer()));
	}

	AuthResponse toResponse(User user, boolean hasSecurityQuestion) {
		JwtService.IssuedToken token = this.jwtService.issue(user.getId(),
				this.userSecurity.passwordChangedAt(user.getId()).orElse(null));
		return new AuthResponse(token.value(), token.expiresAt(),
				new UserDto(user.getId(), user.getEmail(), hasSecurityQuestion));
	}

	private boolean hasQuestion(long userId) {
		return this.userSecurity.find(userId).map(UserSecurity.State::hasQuestion).orElse(false);
	}

	private void validateQuestion(Short questionId, String answer) {
		if (questionId == null || this.questions.findById(questionId).isEmpty()) {
			throw invalidField("questionId", "must be one of the security questions");
		}
		if (SecurityAnswers.normalize(answer).length() < this.resetProperties.answerMinChars()) {
			throw invalidField("answer",
					"must have at least " + this.resetProperties.answerMinChars() + " letters or digits");
		}
	}

	private static ApiException invalidField(String field, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Request is invalid",
				Map.of("fieldErrors", List.of(new ApiError.FieldError(field, message))));
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, ErrorCodes.EMAIL_TAKEN, "Email already registered");
	}

}
