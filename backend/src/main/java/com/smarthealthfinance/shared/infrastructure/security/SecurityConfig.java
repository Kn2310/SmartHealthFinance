package com.smarthealthfinance.shared.infrastructure.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * API stateless protegida por OAuth2 Resource Server (JWT emitido pelo IdP OIDC — ver ADR-0002).
 * Autorização por Workspace é responsabilidade da camada Application, não do filtro HTTP.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

	private static final String[] API_DOCS = { "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**" };

	@Bean
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, SecurityProperties properties,
			JsonMapper jsonMapper) throws Exception {
		ApiErrorSecurityHandler errorHandler = new ApiErrorSecurityHandler(jsonMapper);

		http.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> {
				auth.requestMatchers("/error").permitAll();
				// Em produção o actuator roda em porta própria (MANAGEMENT_SERVER_PORT), não exposta pelo proxy.
				auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
						"/actuator/prometheus").permitAll();
				if (properties.publicApiDocs()) {
					auth.requestMatchers(API_DOCS).permitAll();
				}
				auth.requestMatchers("/api/v1/**").authenticated();
				auth.anyRequest().denyAll();
			})
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler))
			.exceptionHandling(ex -> ex.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler));

		return http.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.corsAllowedOrigins());
		cors.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
				HttpMethod.PATCH.name(), HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
		cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
				"Idempotency-Key", "X-Correlation-Id"));
		cors.setExposedHeaders(List.of("X-Correlation-Id"));
		cors.setAllowCredentials(false);
		cors.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

}
