package com.newspal.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.newspal.auth.SecurityQuestions.Question;
import com.newspal.auth.dto.AuthDtos.AuthResponse;
import com.newspal.auth.dto.AuthDtos.ResetPasswordRequest;
import com.newspal.auth.dto.AuthDtos.ResetQuestionResponse;
import com.newspal.common.ApiException;
import com.newspal.common.ErrorCodes;
import com.newspal.common.GlobalExceptionHandler;
import com.newspal.config.ResetProperties;
import com.newspal.user.User;
import com.newspal.user.UserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password reset with a security question (SPEC-v2 §7). Unknown emails and accounts
 * without a question get a stable decoy question and the same responses and lockout as
 * a real account, so the flow can't be used to find out who is registered.
 */
@Service
public class PasswordResetService {

	private static final Duration IP_WINDOW = Duration.ofHours(1);

	private final UserRepository users;

	private final UserSecurity userSecurity;

	private final SecurityQuestions questions;

	private final SecurityAnswers answers;

	private final PasswordEncoder passwordEncoder;

	private final AuthService authService;

	private final PasswordChangeValidator passwordChanges;

	private final ResetProperties properties;

	private final SecretKey decoyKey;

	private final Clock clock;

	/** Client IP → reset requests in the current hour. */
	private final Cache<String, AtomicInteger> perIp;

	/** Normalised email → lockout state, for emails without a real question. */
	private final Cache<String, Decoy> decoys;

	public PasswordResetService(UserRepository users, UserSecurity userSecurity, SecurityQuestions questions,
			SecurityAnswers answers, PasswordEncoder passwordEncoder, AuthService authService,
			PasswordChangeValidator passwordChanges, ResetProperties properties, SecretKey jwtSigningKey,
			Clock clock) {
		this.users = users;
		this.userSecurity = userSecurity;
		this.questions = questions;
		this.answers = answers;
		this.passwordEncoder = passwordEncoder;
		this.authService = authService;
		this.passwordChanges = passwordChanges;
		this.properties = properties;
		this.decoyKey = jwtSigningKey;
		this.clock = clock;
		this.perIp = Caffeine.newBuilder().expireAfterWrite(IP_WINDOW).maximumSize(50_000).build();
		this.decoys = Caffeine.newBuilder()
			.expireAfterWrite(properties.lockout().plus(Duration.ofHours(1)))
			.maximumSize(50_000)
			.build();
	}

	/** The question to show for {@code email}. */
	public ResetQuestionResponse question(String email, String clientIp) {
		countRequest(clientIp);
		String normalized = AuthService.normalizeEmail(email);
		Question question = this.userSecurity.findByEmail(normalized)
			.filter(UserSecurity.State::hasQuestion)
			.flatMap((state) -> this.questions.findById(state.questionId()))
			.orElseGet(() -> decoyQuestion(normalized));
		return new ResetQuestionResponse(question.id(), question.text());
	}

	/**
	 * Sets a new password if the answer matches, and signs the user in. Not
	 * transactional: a wrong answer must stay counted even though the call fails.
	 */
	public AuthResponse reset(ResetPasswordRequest request, String clientIp) {
		countRequest(clientIp);
		String email = AuthService.normalizeEmail(request.email());
		Instant now = this.clock.instant();
		Optional<UserSecurity.State> found = this.userSecurity.findByEmail(email)
			.filter(UserSecurity.State::hasQuestion);
		if (found.isEmpty()) {
			this.answers.matches(request.answer(), null);
			throw decoyWrongAnswer(email, now);
		}
		UserSecurity.State state = found.get();
		if (state.isLocked(now)) {
			throw locked(state.lockedUntil(), now);
		}
		if (!this.answers.matches(request.answer(), state.answerHash())) {
			Instant lockUntil = now.plus(this.properties.lockout());
			int left = this.userSecurity.recordWrongAnswer(state.userId(), this.properties.maxAttempts(), lockUntil);
			throw (left == 0) ? locked(lockUntil, now) : wrongAnswer(left);
		}
		// Millisecond precision, the same as the token claim it is compared with.
		Instant changedAt = now.truncatedTo(ChronoUnit.MILLIS);
		this.userSecurity.changePassword(state.userId(), this.passwordEncoder.encode(request.newPassword()),
				changedAt);
		this.passwordChanges.forget(state.userId());
		User user = this.users.findById(state.userId()).orElseThrow();
		return this.authService.toResponse(user, true);
	}

	/** For tests: forget per-IP counts and decoy lockouts. */
	void clearLimits() {
		this.perIp.invalidateAll();
		this.decoys.invalidateAll();
	}

	private void countRequest(String clientIp) {
		AtomicInteger count = this.perIp.get((clientIp != null) ? clientIp : "unknown", (k) -> new AtomicInteger());
		if (count.incrementAndGet() > this.properties.hourlyPerIp()) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RESET_RATE_LIMITED,
					"Too many reset attempts from this network; try again later",
					Map.of(GlobalExceptionHandler.RETRY_AFTER_SECONDS, IP_WINDOW.toSeconds()));
		}
	}

	private ApiException decoyWrongAnswer(String email, Instant now) {
		Decoy decoy = this.decoys.get(email, (k) -> new Decoy());
		synchronized (decoy) {
			if (decoy.lockedUntil != null && now.isBefore(decoy.lockedUntil)) {
				return locked(decoy.lockedUntil, now);
			}
			if (++decoy.attempts >= this.properties.maxAttempts()) {
				decoy.attempts = 0;
				decoy.lockedUntil = now.plus(this.properties.lockout());
				return locked(decoy.lockedUntil, now);
			}
			return wrongAnswer(this.properties.maxAttempts() - decoy.attempts);
		}
	}

	/** The same question every time for the same email, picked by a keyed hash. */
	private Question decoyQuestion(String email) {
		List<Question> all = this.questions.findAll();
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(this.decoyKey);
			byte[] digest = mac.doFinal(("security-question-decoy:" + email).getBytes(StandardCharsets.UTF_8));
			return all.get(Math.floorMod(ByteBuffer.wrap(digest).getInt(), all.size()));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 unavailable", ex);
		}
	}

	private static ApiException wrongAnswer(int attemptsLeft) {
		return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.WRONG_ANSWER, "That answer doesn't match",
				Map.of("attemptsLeft", attemptsLeft));
	}

	private static ApiException locked(Instant until, Instant now) {
		long seconds = Math.max(1, Duration.between(now, until).toSeconds());
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RESET_LOCKED,
				"Too many attempts. Try again tomorrow.", Map.of(GlobalExceptionHandler.RETRY_AFTER_SECONDS, seconds));
	}

	private static final class Decoy {

		int attempts;

		Instant lockedUntil;

	}

}
