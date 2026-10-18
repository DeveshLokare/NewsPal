package com.newspal.common;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CursorCodecTests {

	@Test
	void roundTrips() {
		Instant at = Instant.parse("2026-10-17T05:00:00.123456Z");
		CursorCodec.Position position = CursorCodec.decode(CursorCodec.encode(at, 42));
		assertThat(position.at()).isEqualTo(at);
		assertThat(position.id()).isEqualTo(42);
	}

	@Test
	void blankIsNoCursor() {
		assertThat(CursorCodec.decode(null)).isNull();
		assertThat(CursorCodec.decode(" ")).isNull();
	}

	@Test
	void garbageIsBadCursor() {
		for (String bad : new String[] { "!!!", "bm8tYmFy", "eWVzdGVyZGF5fDE" }) {
			assertThatExceptionOfType(ApiException.class).isThrownBy(() -> CursorCodec.decode(bad))
				.satisfies((ex) -> assertThat(ex.code()).isEqualTo(ErrorCodes.BAD_CURSOR));
		}
	}

}
