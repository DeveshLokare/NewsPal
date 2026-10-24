package com.newspal.auth;

import com.newspal.auth.dto.AuthDtos.AuthResponse;
import com.newspal.auth.dto.AuthDtos.ResetPasswordRequest;
import com.newspal.auth.dto.AuthDtos.ResetQuestionRequest;
import com.newspal.auth.dto.AuthDtos.ResetQuestionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forgot-password flow (SPEC-v2 §7.2). Public; Render's proxy headers give the client
 * IP ({@code server.forward-headers-strategy}).
 */
@RestController
@RequestMapping("/api/auth/password")
public class PasswordResetController {

	private final PasswordResetService resets;

	public PasswordResetController(PasswordResetService resets) {
		this.resets = resets;
	}

	@PostMapping("/question")
	public ResetQuestionResponse question(@Valid @RequestBody ResetQuestionRequest request,
			HttpServletRequest http) {
		return this.resets.question(request.email(), http.getRemoteAddr());
	}

	@PostMapping("/reset")
	public AuthResponse reset(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
		return this.resets.reset(request, http.getRemoteAddr());
	}

}
