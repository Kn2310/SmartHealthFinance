package com.smarthealthfinance.shared.presentation.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class ErrorContractIT extends IntegrationTest {

	@Test
	void validationErrorsListFieldDetails() {
		assertThat(mvc.post()
			.uri("/api/v1/test-probe/validation")
			.with(jwt())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "", "count": 0}
					"""))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("VALIDATION_FAILED"))
			.hasPathSatisfying("$.message", message -> assertThat(message).asString().isNotBlank())
			.hasPathSatisfying("$.traceId", traceId -> assertThat(traceId).asString().isNotBlank())
			.hasPathSatisfying("$.details[*].field",
					fields -> assertThat(fields).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
						.containsExactlyInAnyOrder("name", "count"));
	}

	@Test
	void malformedJsonIsRejected() {
		assertThat(mvc.post()
			.uri("/api/v1/test-probe/validation")
			.with(jwt())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{not-json"))
			.hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("MALFORMED_REQUEST"));
	}

	@Test
	void unknownResourceReturnsNotFound() {
		assertThat(mvc.get().uri("/api/v1/does-not-exist").with(jwt())).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("NOT_FOUND"))
			.hasPathSatisfying("$.traceId", traceId -> assertThat(traceId).asString().isNotBlank());
	}

	@Test
	void unsupportedMethodReturnsMethodNotAllowed() {
		assertThat(mvc.delete().uri("/api/v1/users/me").with(jwt())).hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
			.bodyJson()
			.hasPathSatisfying("$.code", code -> assertThat(code).asString().isEqualTo("METHOD_NOT_ALLOWED"));
	}

	@Test
	void unexpectedErrorsDoNotLeakInternals() {
		assertThat(mvc.get().uri("/api/v1/test-probe/failure").with(jwt()))
			.hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
			.bodyText()
			.contains("INTERNAL_ERROR")
			.doesNotContain("hunter2")
			.doesNotContain("12345-6")
			.doesNotContain("IllegalStateException");
	}

}
