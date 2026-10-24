package com.newspal.ingestion;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;

import com.newspal.TestcontainersConfiguration;
import com.newspal.support.FakeNewsProvider;
import com.newspal.support.FakeProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Job passes, the daily cleanup window and the external trigger (SPEC-v2 §5, §6).
 * The test profile sets {@code newspal.jobs.token=test-job-token}.
 */
@Import({ TestcontainersConfiguration.class, FakeProviderConfig.class })
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobTests {

	private static final String RUN = "/api/internal/jobs/run";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JobRunner jobs;

	@Autowired
	private FakeNewsProvider provider;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void reset() throws InterruptedException {
		awaitIdle();
		this.provider.reset();
		this.jdbc.sql("DELETE FROM articles").update();
		this.jdbc.sql("DELETE FROM job_runs").update();
		this.jdbc.sql("DELETE FROM api_usage").update();
		this.jdbc.sql("UPDATE categories SET last_fetched_at = NULL").update();
	}

	@Test
	void passRefreshesEveryCategory() {
		assertThat(this.jobs.runNow()).isTrue();
		assertThat(this.provider.calls).hasValue(8);
	}

	@Test
	void cleanupRunsAtMostOncePerWindow() {
		this.provider.enabled = false;
		insertExpiredArticle("https://example.com/old-1");
		this.jobs.runNow();
		assertThat(articleCount()).as("first pass cleans up").isZero();

		insertExpiredArticle("https://example.com/old-2");
		this.jobs.runNow();
		this.jobs.cleanUpIfDue();
		assertThat(articleCount()).as("already cleaned up today").isEqualTo(1);

		this.jdbc.sql("UPDATE job_runs SET last_run_at = now() - interval '24 hours' WHERE name = 'cleanup'").update();
		this.jobs.runNow();
		assertThat(articleCount()).as("due again a day later").isZero();
	}

	@Test
	void triggerWithoutTheRightTokenLooksMissing() throws Exception {
		this.mvc.perform(post(RUN)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		this.mvc.perform(post(RUN).header(JobController.TOKEN_HEADER, "wrong-token"))
			.andExpect(status().isNotFound());
		assertThat(this.jobs.isRunning()).isFalse();
		assertThat(this.provider.calls).hasValue(0);
	}

	@Test
	void triggerStartsOnePassAtATime() throws Exception {
		CountDownLatch gate = new CountDownLatch(1);
		this.provider.latestGate = gate;
		this.mvc.perform(post(RUN).header(JobController.TOKEN_HEADER, "test-job-token"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.started").value(true));
		this.mvc.perform(post(RUN).header(JobController.TOKEN_HEADER, "test-job-token"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.started").value(false));
		assertThat(this.jobs.runNow()).as("in-process timer skips too").isFalse();
		gate.countDown();
		awaitIdle();
		assertThat(this.provider.calls).hasValue(8);
	}

	private void awaitIdle() {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (this.jobs.isRunning() && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(this.jobs.isRunning()).as("job pass finished").isFalse();
	}

	private void insertExpiredArticle(String url) {
		this.jdbc.sql("""
				INSERT INTO articles (url_hash, url, title, published_at, category_id)
				VALUES (md5(?) || md5(?), ?, 'Old', ?, (SELECT min(id) FROM categories))""")
			.params(url, url + "#", url, Timestamp.from(Instant.now().minus(Duration.ofDays(8))))
			.update();
	}

	private int articleCount() {
		return this.jdbc.sql("SELECT count(*) FROM articles").query(Integer.class).single();
	}

}
