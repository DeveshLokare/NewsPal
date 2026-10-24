package com.newspal.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security (SPEC §8.3). Tokens are HS256, issued by
 * {@link com.newspal.auth.JwtService} and validated by Spring Security's resource
 * server support.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	public static final String ISSUER = "newspal";

	private static final String UNAUTHORIZED_BODY = """
			{"code":"UNAUTHORIZED","message":"Authentication required","fieldErrors":[]}""";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Value("${newspal.cors.allowed-origin-patterns:}") List<String> corsOrigins) throws Exception {
		AuthenticationEntryPoint entryPoint = (request, response, ex) -> writeUnauthorized(response);
		if (!corsOrigins.isEmpty()) {
			// Only for running the Flutter app in a browser during development; the
			// mobile app doesn't need CORS.
			CorsConfiguration cors = new CorsConfiguration();
			cors.setAllowedOriginPatterns(corsOrigins);
			cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
			cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
			UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
			source.registerCorsConfiguration("/api/**", cors);
			http.cors((c) -> c.configurationSource(source));
		}
		http.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests((auth) -> auth
				.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
				// Guarded by its own X-Job-Token header instead (JobController).
				.requestMatchers(HttpMethod.POST, "/api/internal/jobs/run").permitAll()
				.requestMatchers("/actuator/health", "/error").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer((oauth) -> oauth.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
			.exceptionHandling((ex) -> ex.authenticationEntryPoint(entryPoint));
		return http.build();
	}

	@Bean
	SecretKey jwtSigningKey(JwtProperties properties) {
		return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(10);
	}

	private static void writeUnauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(UNAUTHORIZED_BODY);
	}

}
