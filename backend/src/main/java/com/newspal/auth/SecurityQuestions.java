package com.newspal.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The fixed list of security questions ({@code security_questions}).
 */
@Repository
public class SecurityQuestions {

	private final JdbcClient jdbc;

	public SecurityQuestions(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Question> findAll() {
		return this.jdbc.sql("SELECT id, text FROM security_questions ORDER BY sort_order, id")
			.query((rs, row) -> new Question(rs.getShort("id"), rs.getString("text")))
			.list();
	}

	public Optional<Question> findById(short id) {
		return this.jdbc.sql("SELECT id, text FROM security_questions WHERE id = ?")
			.param(id)
			.query((rs, row) -> new Question(rs.getShort("id"), rs.getString("text")))
			.optional();
	}

	public record Question(short id, String text) {
	}

}
