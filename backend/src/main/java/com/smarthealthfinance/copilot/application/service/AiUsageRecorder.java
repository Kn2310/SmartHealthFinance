package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.application.port.AiResult;
import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.domain.model.PricingTable;
import com.smarthealthfinance.copilot.domain.repository.AiUsageLedger;
import com.smarthealthfinance.copilot.domain.service.CostCalculator;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.UuidV7;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Registra cada chamada no usage ledger com custo determinístico pela tabela de preços versionada (ADR-0039 §10), em
 * transação própria e só com metadados. Modelo sem preço na tabela custa zero e aparece no log e na métrica: a tabela
 * precisa ser atualizada (runbook).
 */
@Service
public class AiUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiUsageRecorder.class);

    private final AiUsageLedger ledger;
    private final PricingTable pricing;
    private final Clock clock;
    private final MeterRegistry meters;

    public AiUsageRecorder(AiUsageLedger ledger, PricingTable pricing, Clock clock, MeterRegistry meters) {
        this.ledger = ledger;
        this.pricing = pricing;
        this.clock = clock;
        this.meters = meters;
    }

    /** {@code promptVersion} e {@code schemaVersion}: o que foi enviado (ADR-0039 §11), ou vazio. */
    @Transactional
    public AiUsage record(WorkspaceId workspaceId, UserId userId, UUID requestId, AiRequestType type,
                          AiResult result, String promptVersion, String schemaVersion) {
        BigDecimal cost = pricing.find(result.model())
                .map(price -> CostCalculator.cost(price, result.usage()))
                .orElseGet(() -> {
                    meters.counter("shf.copilot.ai.unpriced", "model", result.model()).increment();
                    log.warn("Copilot model without price model={} pricingVersion={}", result.model(),
                            pricing.version());
                    return BigDecimal.ZERO.setScale(CostCalculator.SCALE);
                });
        Instant now = clock.instant();
        AiUsage usage = new AiUsage(UuidV7.generate(now), workspaceId, userId, requestId, type, result.provider(),
                result.model(), result.usage(), cost, pricing.version(), promptVersion, schemaVersion,
                result.latency().toMillis(),
                result.outcome(), now);
        ledger.append(usage);

        String typeTag = type.name();
        meters.counter("shf.copilot.ai.tokens", "type", typeTag, "kind", "input").increment(result.usage().input());
        meters.counter("shf.copilot.ai.tokens", "type", typeTag, "kind", "output").increment(result.usage().output());
        meters.counter("shf.copilot.ai.tokens", "type", typeTag, "kind", "cache_read")
                .increment(result.usage().cacheRead());
        meters.counter("shf.copilot.ai.tokens", "type", typeTag, "kind", "cache_write")
                .increment(result.usage().cacheWrite());
        meters.counter("shf.copilot.ai.cost.usd", "type", typeTag, "model", result.model())
                .increment(cost.doubleValue());
        log.info("Copilot usage recorded requestId={} workspaceId={} type={} model={} outcome={} latencyMs={}",
                requestId, workspaceId, type, result.model(), result.outcome(), usage.latencyMs());
        return usage;
    }
}
