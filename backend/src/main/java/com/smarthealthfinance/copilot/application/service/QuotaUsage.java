package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.domain.model.CopilotLimits;
import com.smarthealthfinance.copilot.domain.repository.AiUsageLedger;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Consumo das quotas do Workspace no fuso de negócio (ADR-0039 §10), contado no usage ledger: a mesma regra serve à
 * admissão ({@link CopilotAdmission}) e à consulta de uso (ADR-0040 §8), para nunca divergirem.
 */
@Service
public class QuotaUsage {

    /** Uma janela de quota: perguntas feitas, limite e quando zera. */
    public record Window(long used, long limit, Instant resetsAt) {

        public boolean exhausted() {
            return used >= limit;
        }
    }

    /** Janelas de perguntas do mês e do dia de negócio. */
    public record Snapshot(Window month, Window day) {
    }

    private final AiUsageLedger ledger;
    private final CopilotLimits limits;
    private final Clock clock;

    public QuotaUsage(AiUsageLedger ledger, CopilotLimits limits, Clock clock) {
        this.ledger = ledger;
        this.limits = limits;
        this.clock = clock;
    }

    public Snapshot questions(WorkspaceId workspace) {
        ZonedDateTime now = clock.instant().atZone(limits.zone());
        LocalDate today = now.toLocalDate();
        Instant dayStart = today.atStartOfDay(limits.zone()).toInstant();
        Instant nextDay = today.plusDays(1).atStartOfDay(limits.zone()).toInstant();
        Instant monthStart = monthStart(today);
        Instant nextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(limits.zone()).toInstant();
        return new Snapshot(
                new Window(ledger.countQuestions(workspace, monthStart, nextMonth), limits.monthlyQuestions(),
                        nextMonth),
                new Window(ledger.countQuestions(workspace, dayStart, nextDay), limits.dailyQuestions(), nextDay));
    }

    /** Tokens de entrada do mês de negócio acima do teto. */
    public boolean monthlyInputTokensExhausted(WorkspaceId workspace) {
        LocalDate today = clock.instant().atZone(limits.zone()).toLocalDate();
        Instant nextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(limits.zone()).toInstant();
        return ledger.sumInputTokens(workspace, monthStart(today), nextMonth) >= limits.monthlyInputTokens();
    }

    private Instant monthStart(LocalDate today) {
        return today.withDayOfMonth(1).atStartOfDay(limits.zone()).toInstant();
    }
}
