package com.smarthealthfinance.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints existentes apenas em testes para exercitar o contrato de erro.
 */
@TestComponent
@RestController
@RequestMapping("/api/v1/test-probe")
class ProbeController {

	static final String SENSITIVE_DETAIL = "db password=hunter2 account=12345-6";

	@PostMapping("/validation")
	ResponseEntity<Void> validate(@Valid @RequestBody ProbeRequest request) {
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/failure")
	void fail() {
		throw new IllegalStateException(SENSITIVE_DETAIL);
	}

	record ProbeRequest(@NotBlank String name, @Positive int count) {
	}

}
