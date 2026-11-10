package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.consents.application.port.ConsentReader;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.copilot.application.dto.Admission;
import com.smarthealthfinance.copilot.application.port.RateLimiter;
import com.smarthealthfinance.copilot.domain.enums.AdmissionCode;
import com.smarthealthfinance.copilot.domain.model.CopilotLimits;
import com.smarthealthfinance.copilot.domain.repository.AiUsageLedger;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Portão do Copilot antes de qualquer dado ir ao provedor (ADR-0039 §10, §13): Workspace (membership) → Copilot ligado
 * → consentimento {@code COPILOT_DATA_USE} → rate limit por pessoa → quotas do Workspace no fuso de negócio. As
 * quotas contam no usage ledger (Postgres); o Redis só guarda o token bucket.
 */
@Service
public class CopilotAdmission {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final ConsentReader consents;
    private final RateLimiter rateLimiter;
    private final AiUsageLedger ledger;
    private final CopilotLimits limits;
    private final Clock clock;
    private final MeterRegistry meters;

    public CopilotAdmission(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, ConsentReader consents,
                            RateLimiter rateLimiter, AiUsageLedger ledger, CopilotLimits limits, Clock clock,
                            MeterRegistry meters) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.consents = consents;
        this.rateLimiter = rateLimiter;
        this.ledger = ledger;
        this.limits = limits;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Sem transação envolvendo o todo: cada leitura tem a própria, e a chamada ao Redis nunca segura uma conexão do
     * banco.
     *
     * @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException sem membership
     */
    public Admission admit(UUID workspaceId) {
        WorkspaceId workspace = new WorkspaceId(workspaceId);
        UserId user = currentUser.requireCurrentUserId();
        accessGuard.requireMember(user, workspace);

        Admission admission = decide(workspace, user);
        meters.counter("shf.copilot.admission", "code", admission.code().name()).increment();
        return admission;
    }

    private Admission decide(WorkspaceId workspace, UserId user) {
        if (!limits.enabled()) {
            return new Admission(AdmissionCode.UNAVAILABLE, workspace, user, null, null);
        }
        if (!consents.isActive(workspace, user, ConsentPurpose.COPILOT_DATA_USE)) {
            return new Admission(AdmissionCode.CONSENT_REQUIRED, workspace, user, null, null);
        }

        RateLimiter.Decision rate = rateLimiter.tryAcquire(workspace, user, limits.bucketCapacity(),
                limits.refillInterval());
        switch (rate.status()) {
            case UNAVAILABLE -> {
                return new Admission(AdmissionCode.UNAVAILABLE, workspace, user, null, null);
            }
            case DENIED -> {
                return new Admission(AdmissionCode.RATE_LIMITED, workspace, user, rate.retryAfter(), null);
            }
            case ALLOWED -> {
                // segue para as quotas
            }
        }

        ZonedDateTime now = clock.instant().atZone(limits.zone());
        LocalDate today = now.toLocalDate();
        Instant dayStart = today.atStartOfDay(limits.zone()).toInstant();
        Instant nextDay = today.plusDays(1).atStartOfDay(limits.zone()).toInstant();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(limits.zone()).toInstant();
        Instant nextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(limits.zone()).toInstant();

        if (ledger.countQuestions(workspace, monthStart, nextMonth) >= limits.monthlyQuestions()
                || ledger.sumInputTokens(workspace, monthStart, nextMonth) >= limits.monthlyInputTokens()) {
            return new Admission(AdmissionCode.QUOTA_EXCEEDED, workspace, user, null, nextMonth);
        }
        if (ledger.countQuestions(workspace, dayStart, nextDay) >= limits.dailyQuestions()) {
            return new Admission(AdmissionCode.QUOTA_EXCEEDED, workspace, user, null, nextDay);
        }
        return new Admission(AdmissionCode.ADMITTED, workspace, user, null, null);
    }
}
