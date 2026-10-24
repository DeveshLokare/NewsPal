package com.newspal.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityAnswersTests {

	private final SecurityAnswers answers = new SecurityAnswers(new BCryptPasswordEncoder(4));

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			'  Mr. Whiskers '      | mr whiskers
			MR   WHISKERS!         | mr whiskers
			'O''Brien Street'      | obrien street
			Hyun-a                 | hyuna
			'São Paulo'            | são paulo
			""")
	void normalizesCaseSpacesAndPunctuation(String answer, String expected) {
		assertThat(SecurityAnswers.normalize(answer)).isEqualTo(expected);
	}

	@Test
	void matchesNormalizedAnswers() {
		String hash = this.answers.hash("Mr. Whiskers");
		assertThat(hash).doesNotContain("hisker");
		assertThat(this.answers.matches("mr whiskers", hash)).isTrue();
		assertThat(this.answers.matches("Mr Whiskers!!", hash)).isTrue();
		assertThat(this.answers.matches("Mr Wiskers", hash)).isFalse();
	}

	@Test
	void noStoredAnswerNeverMatches() {
		assertThat(this.answers.matches("newspal-dummy-answer", null)).isFalse();
		assertThat(SecurityAnswers.normalize(null)).isEmpty();
	}

}
