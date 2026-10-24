package com.newspal.auth;

import com.newspal.auth.dto.AuthDtos.AuthResponse;
import com.newspal.auth.dto.AuthDtos.LoginRequest;
import com.newspal.auth.dto.AuthDtos.RegisterRequest;
import com.newspal.auth.dto.AuthDtos.SecurityQuestionDto;
import com.newspal.auth.dto.AuthDtos.SetSecurityQuestionRequest;
import com.newspal.auth.dto.AuthDtos.UserDto;
import java.util.List;

import com.newspal.common.CurrentUser;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/auth/register")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
		return this.authService.register(request);
	}

	@PostMapping("/auth/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return this.authService.login(request);
	}

	/** Lets the app confirm a stored token is still valid (splash screen). */
	@GetMapping("/me")
	public UserDto me(@AuthenticationPrincipal Jwt jwt) {
		return this.authService.currentUser(CurrentUser.id(jwt));
	}

	/** The questions offered at registration and in Settings (SPEC-v2 §7.2). */
	@GetMapping("/auth/security-questions")
	public List<SecurityQuestionDto> securityQuestions() {
		return this.authService.securityQuestions();
	}

	@PutMapping("/me/security-question")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void setSecurityQuestion(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody SetSecurityQuestionRequest request) {
		this.authService.setSecurityQuestion(CurrentUser.id(jwt), request);
	}

}
