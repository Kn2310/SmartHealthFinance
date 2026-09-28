package com.smarthealthfinance.identity.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.smarthealthfinance.support.IntegrationTest;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class SecurityIT extends IntegrationTest {

	@Test
	void apiRequiresAuthentication() {
		assertThat(mvc.get().uri("/api/v1/users/me")).hasStatus(HttpStatus.UNAUTHORIZED)
			.hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("UNAUTHENTICATED"))
			.hasPathSatisfying("$.traceId", traceId -> assertThat(traceId).asString().isNotBlank());
	}

	@Test
	void healthIsPublic() {
		assertThat(mvc.get().uri("/actuator/health")).hasStatus(HttpStatus.OK);
	}

	@Test
	void unknownNonApiPathsAreNotServed() {
		assertThat(mvc.get().uri("/admin").with(jwt())).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void corsAllowsOnlyConfiguredOrigins() {
		assertThat(mvc.options()
			.uri("/api/v1/users/me")
			.header(HttpHeaders.ORIGIN, "http://localhost:3000")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.hasStatus(HttpStatus.OK)
			.hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000");

		assertThat(mvc.options()
			.uri("/api/v1/users/me")
			.header(HttpHeaders.ORIGIN, "https://evil.example")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.hasStatus(HttpStatus.FORBIDDEN)
			.doesNotContainHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
	}

	@Test
	void correlationIdIsPropagatedOrGenerated() {
		assertThat(mvc.get().uri("/actuator/health").header("X-Correlation-Id", "req-42"))
			.hasHeader("X-Correlation-Id", "req-42");

		assertThat(mvc.get().uri("/actuator/health").header("X-Correlation-Id", "bad\nvalue"))
			.headers()
			.hasHeaderSatisfying("X-Correlation-Id",
					values -> assertThat(values).singleElement(InstanceOfAssertFactories.STRING)
						.isNotEqualTo("bad\nvalue")
						.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
	}

}
