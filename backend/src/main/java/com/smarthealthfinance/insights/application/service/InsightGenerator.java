package com.smarthealthfinance.insights.application.service;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightInput;
import com.smarthealthfinance.insights.domain.model.ScoredCandidate;
import com.smarthealthfinance.insights.domain.service.InsightEngine;
import com.smarthealthfinance.insights.domain.service.InsightFingerprint;
import com.smarthealthfinance.insights.domain.service.InsightReconciler;
import com.smarthealthfinance.insights.domain.service.InsightRules;
import com.smarthealthfinance.insights.domain.service.InsightScoring;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.domain.UuidV7;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gera os insights de um Workspace (ADR-0022 §4): detecta, pontua, reconcilia com os já guardados e grava só o que
 * mudou. Chamado com o Workspace já bloqueado (job), dentro da transação de quem chama.
 */
@Service
public class InsightGenerator {

    private final InsightInputLoader loader;
    private final InsightStore store;
    private final Clock clock;
    private final MeterRegistry meters;

    public InsightGenerator(InsightInputLoader loader, InsightStore store, Clock clock, MeterRegistry meters) {
        this.loader = loader;
        this.store = store;
        this.clock = clock;
        this.meters = meters;
    }

    /** Contagens da geração; só ids e números vão para log. */
    public record Outcome(int detected, int created, int changed) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome generate(WorkspaceId workspaceId) {
        Timer.Sample sample = Timer.start(meters);
        Instant now = clock.instant();
        InsightInput input = loader.load(workspaceId);
        List<Insight> existing = store.findAll(workspaceId);
        List<ScoredCandidate> scored = score(workspaceId.value(), input, existing, now);
        List<Insight> changed = InsightReconciler.reconcile(workspaceId.value(), InsightRules.ALGORITHM_VERSION,
                input.period().key(), existing, scored, now, () -> UuidV7.generate(now));
        store.save(changed);
        store.finishGeneration(workspaceId, InsightRules.ALGORITHM_VERSION, now, input.period().today());

        Set<UUID> known = existing.stream().map(Insight::id).collect(Collectors.toSet());
        List<Insight> created = changed.stream().filter(i -> !known.contains(i.id())).toList();
        created.forEach(i -> meters.counter("shf.insights.detected", "detector", i.detector().name(),
                "importance", i.importance().name()).increment());
        sample.stop(Timer.builder("shf.insights.generation").tag("result", "ok").register(meters));
        return new Outcome(scored.size(), created.size(), changed.size());
    }

    /** Detecção e pontuação puras sobre a entrada (também usadas pelo teste de paridade). */
    public static List<ScoredCandidate> score(UUID workspaceId, InsightInput input, List<Insight> existing,
                                              Instant now) {
        List<InsightCandidate> candidates = InsightEngine.detect(input);
        Money reference = InsightEngine.reference(input);
        return candidates.stream()
                .map(c -> InsightScoring.score(c, InsightFingerprint.of(c.detector(), workspaceId, c.periodKey(),
                        c.subject()), reference, existing, now))
                .toList();
    }
}
