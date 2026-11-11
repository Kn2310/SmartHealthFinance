package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.application.InMemoryAiUsageLedger;
import com.smarthealthfinance.copilot.application.port.AiResult;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.domain.model.ModelPrice;
import com.smarthealthfinance.copilot.domain.model.PricingTable;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Usage ledger (ADR-0039 §10): custo pela tabela versionada, só metadados. */
@ExtendWith(OutputCaptureExtension.class)
class AiUsageRecorderTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final WorkspaceId WORKSPACE = new WorkspaceId(UUID.randomUUID());
    private static final UserId USER = new UserId(UUID.randomUUID());

    private final InMemoryAiUsageLedger ledger = new InMemoryAiUsageLedger();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PricingTable pricing = PricingTable.of("pricing-2026-11", List.of(new ModelPrice(
            "claude-sonnet-5-5", new ModelPrice.Rates(new BigDecimal("2.00"), new BigDecimal("10.00"),
            new BigDecimal("0.10"), new BigDecimal("2.50")), 0, null)));
    private final AiUsageRecorder recorder =
            new AiUsageRecorder(ledger, pricing, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    @Test
    void recordsCostVersionTokensAndOutcome() {
        UUID requestId = UUID.randomUUID();
        AiResult result = AiResult.ok("{\"answer\":\"segredo\"}", "anthropic", "claude-sonnet-5-5",
                new TokenUsage(6_000, 600, 2_000, 0), Duration.ofMillis(1_234));

        AiUsage usage = recorder.record(WORKSPACE, USER, requestId, AiRequestType.ANSWER, result,
                "answer-v1@0123abcd", "answer-v1");

        assertThat(ledger.rows).containsExactly(usage);
        assertThat(usage.cost()).isEqualByComparingTo("0.018200");
        assertThat(usage.pricingVersion()).isEqualTo("pricing-2026-11");
        assertThat(usage.promptVersion()).isEqualTo("answer-v1@0123abcd");
        assertThat(usage.schemaVersion()).isEqualTo("answer-v1");
        assertThat(usage.requestId()).isEqualTo(requestId);
        assertThat(usage.latencyMs()).isEqualTo(1_234);
        assertThat(usage.outcome()).isEqualTo(AiOutcome.OK);
        assertThat(usage.createdAt()).isEqualTo(NOW);
        assertThat(usage.id().version()).isEqualTo(7);
        assertThat(meters.counter("shf.copilot.ai.tokens", "type", "ANSWER", "kind", "input").count())
                .isEqualTo(6_000.0);
        assertThat(meters.counter("shf.copilot.ai.tokens", "type", "ANSWER", "kind", "cache_read").count())
                .isEqualTo(2_000.0);
    }

    @Test
    void failedCallIsRecordedWithZeroTokens() {
        AiResult result = AiResult.failed(AiOutcome.TIMEOUT, "anthropic", "claude-sonnet-5-5", TokenUsage.NONE,
                Duration.ofSeconds(25));

        AiUsage usage = recorder.record(WORKSPACE, USER, UUID.randomUUID(), AiRequestType.ANSWER, result,
                "answer-v1@0123abcd", "answer-v1");

        assertThat(usage.cost()).isEqualByComparingTo("0");
        assertThat(usage.outcome()).isEqualTo(AiOutcome.TIMEOUT);
    }

    @Test
    void unpricedModelCostsZeroAndIsFlaggedWithoutContent(CapturedOutput output) {
        AiResult result = AiResult.ok("{\"answer\":\"R$ 9.999,99\"}", "anthropic", "claude-new-model",
                new TokenUsage(10, 10, 0, 0), Duration.ofMillis(5));

        AiUsage usage = recorder.record(WORKSPACE, USER, UUID.randomUUID(), AiRequestType.INTENT, result,
                "answer-v1@0123abcd", "answer-v1");

        assertThat(usage.cost()).isEqualByComparingTo("0");
        assertThat(meters.counter("shf.copilot.ai.unpriced", "model", "claude-new-model").count()).isEqualTo(1.0);
        assertThat(output).contains("model=claude-new-model").doesNotContain("9.999,99").doesNotContain("segredo");
    }
}
