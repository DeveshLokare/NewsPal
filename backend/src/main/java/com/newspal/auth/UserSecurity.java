package com.newspal.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The security question, reset lockout and password-change time of users (SPEC-v2
 * §7.3). Plain JDBC, so counters are updated atomically.
 */
@Repository
public class UserSecurity {

	private static final String COLUMNS = "id, security_question_id, security_answer_hash, reset_locked_until";

	private final JdbcClient jdbc;

	public UserSecurity(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<State> find(long userId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = ?")
			.param(userId)
			.query(UserSecurity::map)
			.optional();
	}

	public Optional<State> findByEmail(String email) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE email = ?")
			.param(email)
			.query(UserSecurity::map)
			.optional();
	}

	public void setQuestion(long userId, short questionId, String answerHash) {
		this.jdbc.sql("UPDATE users SET security_question_id = ?, security_answer_hash = ? WHERE id = ?")
			.params(questionId, answerHash, userId)
			.update();
	}

	/**
	 * Counts a wrong answer; on the {@code maxAttempts}-th it locks resets until
	 * {@code lockUntil} and starts counting again.
	 * @return the attempts left, or {@code 0} if resets are now locked
	 */
	public int recordWrongAnswer(long userId, int maxAttempts, Instant lockUntil) {
		return this.jdbc.sql("""
				UPDATE users SET
				  reset_locked_until = CASE WHEN reset_failed_attempts + 1 >= :max THEN :until
				                            ELSE reset_locked_until END,
				  reset_failed_attempts = CASE WHEN reset_failed_attempts + 1 >= :max THEN 0
				                               ELSE reset_failed_attempts + 1 END
				WHERE id = :id
				RETURNING CASE WHEN reset_failed_attempts = 0 THEN 0 ELSE :max - reset_failed_attempts END""")
			.param("max", maxAttempts)
			.param("until", Timestamp.from(lockUntil))
			.param("id", userId)
			.query(Integer.class)
			.single();
	}

	/** Forgets wrong answers, e.g. after a successful login. */
	public void clearWrongAnswers(long userId) {
		this.jdbc.sql("UPDATE users SET reset_failed_attempts = 0 WHERE id = ? AND reset_failed_attempts > 0")
			.param(userId)
			.update();
	}

	/**
	 * Sets a new password and records when; tokens that don't carry this time are
	 * rejected from then on (see {@link PasswordChangeValidator}).
	 */
	public void changePassword(long userId, String passwordHash, Instant changedAt) {
		this.jdbc.sql("""
				UPDATE users SET password_hash = ?, password_changed_at = ?, reset_failed_attempts = 0,
				                 reset_locked_until = NULL
				WHERE id = ?""").params(passwordHash, Timestamp.from(changedAt), userId).update();
	}

	/** When the user last reset their password; empty if never, or for unknown users. */
	public Optional<Instant> passwordChangedAt(long userId) {
		return this.jdbc.sql("SELECT password_changed_at FROM users WHERE id = ?")
			.param(userId)
			.query((rs, row) -> rs.getTimestamp(1))
			.optional()
			.map(Timestamp::toInstant);
	}

	private static State map(ResultSet rs, int row) throws SQLException {
		short questionId = rs.getShort("security_question_id");
		Short question = rs.wasNull() ? null : questionId;
		Timestamp lockedUntil = rs.getTimestamp("reset_locked_until");
		return new State(rs.getLong("id"), question, rs.getString("security_answer_hash"),
				(lockedUntil != null) ? lockedUntil.toInstant() : null);
	}

	/**
	 * @param questionId {@code null} until the user sets a security question
	 */
	public record State(long userId, Short questionId, String answerHash, Instant lockedUntil) {

		public boolean hasQuestion() {
			return this.questionId != null && this.answerHash != null;
		}

		public boolean isLocked(Instant now) {
			return this.lockedUntil != null && now.isBefore(this.lockedUntil);
		}

	}

}
