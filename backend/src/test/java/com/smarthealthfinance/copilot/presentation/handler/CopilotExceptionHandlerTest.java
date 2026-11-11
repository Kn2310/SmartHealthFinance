package com.smarthealthfinance.copilot.presentation.handler;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.application.exception.ContextNotFoundException;
import com.smarthealthfinance.copilot.application.exception.ConversationNotFoundException;
import com.smarthealthfinance.copilot.application.exception.CopilotAlreadyAnsweredException;
import com.smarthealthfinance.copilot.application.exception.CopilotConsentRequiredException;
import com.smarthealthfinance.copilot.application.exception.CopilotKeyReusedException;
import com.smarthealthfinance.copilot.application.exception.CopilotQuotaExceededException;
import com.smarthealthfinance.copilot.application.exception.CopilotRateLimitedException;
import com.smarthealthfinance.copilot.application.exception.CopilotRequestInProgressException;
import com.smarthealthfinance.copilot.application.exception.CopilotUnavailableException;
import com.smarthealthfinance.copilot.application.service.SubmitCopilotQuestion;
import com.smarthealthfinance.copilot.application.usecase.GetCopilotUsage;
import com.smarthealthfinance.copilot.application.usecase.ManageConversations;
import com.smarthealthfinance.copilot.application.usecase.SubmitFeedback;
import com.smarthealthfinance.copilot.presentation.controller.CopilotController;
import com.smarthealthfinance.shared.presentation.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tradução de cada falha da API do Copilot para HTTP (ADR-0040 §4), com o controller e os handlers reais e o serviço
 * simulado: status, código estável, {@code Retry-After}, {@code resetsAt} no corpo da quota e nenhuma mensagem com
 * conteúdo. O 503 não tem como ser provocado no teste de ponta a ponta (provedor simulado sempre disponível).
 */
class CopilotExceptionHandlerTest {

    private static final UUID WORKSPACE = UUID.randomUUID();

    private final SubmitCopilotQuestion submit = mock(SubmitCopilotQuestion.class);
    private final GetCopilotUsage usage = mock(GetCopilotUsage.class);
    private final MockMvcTester mvc = MockMvcTester.of(List.of(new CopilotController(submit, usage, mock(ManageConversations.class), mock(SubmitFeedback.class))),
            builder -> {
                builder.setControllerAdvice(new CopilotExceptionHandler(), new GlobalExceptionHandler());
                return builder.build();
            });

    private MvcTestResult ask(RuntimeException failure) {
        when(submit.submit(any(), any(), any(), any(AskCommand.class))).thenThrow(failure);
        return mvc.post().uri("/api/v1/workspaces/{id}/copilot/questions", WORKSPACE)
                .header("Idempotency-Key", "web:abc").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"Pergunta secreta\"}").exchange();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void expect(RuntimeException failure, HttpStatus status, String code) {
        MvcTestResult result = ask(failure);
        assertThat(result).hasStatus(status);
        assertThat(JsonPath.<String>read(body(result), "$.code")).isEqualTo(code);
        assertThat(body(result)).doesNotContain("Pergunta secreta");
    }

    @Test
    void everyErrorOfTheContractHasItsStatusAndStableCode() {
        expect(new CopilotConsentRequiredException(), HttpStatus.FORBIDDEN, "COPILOT_CONSENT_REQUIRED");
        expect(new ContextNotFoundException(), HttpStatus.NOT_FOUND, "COPILOT_CONTEXT_NOT_FOUND");
        expect(new ConversationNotFoundException(), HttpStatus.NOT_FOUND, "COPILOT_CONVERSATION_NOT_FOUND");
        expect(new CopilotRequestInProgressException(), HttpStatus.CONFLICT, "COPILOT_REQUEST_IN_PROGRESS");
        expect(new CopilotAlreadyAnsweredException(), HttpStatus.CONFLICT, "COPILOT_ALREADY_ANSWERED");
        expect(new CopilotKeyReusedException(), HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED");
        expect(new CopilotUnavailableException(), HttpStatus.SERVICE_UNAVAILABLE, "COPILOT_UNAVAILABLE");
        expect(new com.smarthealthfinance.shared.domain.InvalidValueException("question", "REQUIRED"),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void rateLimitCarriesRetryAfterInWholeSecondsRoundedUpWithAtLeastOne() {
        MvcTestResult rounded = ask(new CopilotRateLimitedException(Duration.ofMillis(7_300)));
        assertThat(rounded).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rounded.getResponse().getHeader("Retry-After")).isEqualTo("8");
        assertThat(JsonPath.<String>read(body(rounded), "$.code")).isEqualTo("COPILOT_RATE_LIMITED");

        assertThat(ask(new CopilotRateLimitedException(Duration.ofMillis(1))).getResponse()
                .getHeader("Retry-After")).isEqualTo("1");
        assertThat(ask(new CopilotRateLimitedException(Duration.ofSeconds(20))).getResponse()
                .getHeader("Retry-After")).isEqualTo("20");
    }

    @Test
    void quotaCarriesResetsAtInTheBodyAndNoRetryAfter() {
        MvcTestResult result = ask(new CopilotQuotaExceededException(Instant.parse("2026-12-01T03:00:00Z")));

        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.getResponse().getHeader("Retry-After")).isNull();
        String body = body(result);
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("COPILOT_QUOTA_EXCEEDED");
        assertThat(JsonPath.<String>read(body, "$.resetsAt")).isEqualTo("2026-12-01T03:00:00Z");
        assertThat(JsonPath.<String>read(body, "$.message")).isNotBlank();
    }
}
