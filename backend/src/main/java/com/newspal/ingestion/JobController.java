package com.newspal.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import com.newspal.common.ApiException;
import com.newspal.common.ErrorCodes;
import com.newspal.config.JobProperties;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * External trigger for the background jobs (SPEC-v2 §5.2). A scheduled GitHub Actions
 * workflow calls it every 2 hours, which also wakes the service on Render's free plan.
 * Without the right token it answers 404, as if it didn't exist.
 */
@RestController
public class JobController {

	static final String TOKEN_HEADER = "X-Job-Token";

	private final JobRunner jobs;

	private final JobProperties properties;

	public JobController(JobRunner jobs, JobProperties properties) {
		this.jobs = jobs;
		this.properties = properties;
	}

	@PostMapping("/api/internal/jobs/run")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Map<String, Boolean> run(@RequestHeader(name = TOKEN_HEADER, required = false) String token) {
		if (!this.properties.hasToken() || token == null || !matches(token, this.properties.token())) {
			throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Not found");
		}
		return Map.of("started", this.jobs.startInBackground());
	}

	/** Constant-time comparison, so response times don't reveal the token. */
	private static boolean matches(String given, String expected) {
		return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
	}

}
