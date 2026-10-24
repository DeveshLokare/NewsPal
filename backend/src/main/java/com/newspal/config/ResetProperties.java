package com.newspal.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Password reset limits ({@code newspal.reset.*}, SPEC-v2 §7.4).
 * @param maxAttempts wrong answers before an account's resets are locked
 * @param lockout how long resets stay locked
 * @param hourlyPerIp reset requests (question + answer) allowed per client IP per hour
 * @param answerMinChars shortest accepted answer, after normalising
 */
@ConfigurationProperties("newspal.reset")
public record ResetProperties(@DefaultValue("5") int maxAttempts, @DefaultValue("P1D") Duration lockout,
		@DefaultValue("20") int hourlyPerIp, @DefaultValue("3") int answerMinChars) {
}
