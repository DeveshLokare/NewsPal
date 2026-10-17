package com.newspal.auth;

import com.newspal.auth.dto.AuthDtos.AuthResponse;
import com.newspal.auth.dto.AuthDtos.LoginRequest;
import com.newspal.auth.dto.AuthDtos.RegisterRequest;
import com.newspal.auth.dto.AuthDtos.UserDto;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
		return this.authService.currentUser(Long.parseLong(jwt.getSubject()));
	}

}
