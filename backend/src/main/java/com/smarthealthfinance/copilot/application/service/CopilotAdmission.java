package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.consents.application.port.ConsentReader;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.copilot.application.dto.Admission;
import com.smarthealthfinance.copilot.application.port.EntryContextResolver;
import com.smarthealthfinance.copilot.application.port.RateLimiter;
import com.smarthealthfinance.copilot.domain.enums.AdmissionCode;
import com.smarthealthfinance.copilot.domain.model.CopilotLimits;
import com.smarthealthfinance.copilot.domain.model.EntryContext;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Portão do Copilot antes de qualquer dado ir ao provedor (ADR-0039 §10, §13; ADR-0040 §4): Workspace (membership) →
 * Copilot ligado → consentimento {@code COPILOT_DATA_USE} → contexto de entrada → rate limit por pessoa → quotas do
 * Workspace no fuso de negócio. O contexto é validado antes do rate limit para um alvo inválido não gastar quota. As
 * quotas contam no usage ledger (Postgres) por {@link QuotaUsage}; o Redis só guarda o token bucket.
 */
@Service
public class CopilotAdmission {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final ConsentReader consents;
    private final EntryContextResolver contexts;
    private final RateLimiter rateLimiter;
    private final QuotaUsage quota;
    private final CopilotLimits limits;
    private final MeterRegistry meters;

    public CopilotAdmission(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, ConsentReader consents,
                            EntryContextResolver contexts, RateLimiter rateLimiter, QuotaUsage quota,
                            CopilotLimits limits, MeterRegistry meters) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.consents = consents;
        this.contexts = contexts;
        this.rateLimiter = rateLimiter;
        this.quota = quota;
        this.limits = limits;
        this.meters = meters;
    }

    /** Autentica e confere a membership: a única etapa que a API faz antes da idempotência (ADR-0040 §4). */
    public Member requireMember(UUID workspaceId) {
        WorkspaceId workspace = new WorkspaceId(workspaceId);
        UserId user = currentUser.requireCurrentUserId();
        accessGuard.requireMember(user, workspace);
        return new Member(workspace, user);
    }

    /** A pessoa autenticada e o Workspace de que ela é membro. */
    public record Member(WorkspaceId workspaceId, UserId userId) {
    }

    /** Sem contexto de entrada. */
    public Admission admit(UUID workspaceId) {
        return admit(workspaceId, null);
    }

    /**
     * Sem transação envolvendo o todo: cada leitura tem a própria, e a chamada ao Redis nunca segura uma conexão do
     * banco.
     *
     * @param context contexto de entrada, ou nulo
     * @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException sem membership
     * @throws com.smarthealthfinance.copilot.application.exception.ContextNotFoundException alvo do contexto fora do
     *         Workspace
     */
    public Admission admit(UUID workspaceId, EntryContext context) {
        Member member = requireMember(workspaceId);
        Admission admission = decide(member.workspaceId(), member.userId(), context);
        meters.counter("shf.copilot.admission", "code", admission.code().name()).increment();
        return admission;
    }

    private Admission decide(WorkspaceId workspace, UserId user, EntryContext context) {
        if (!limits.enabled()) {
            return new Admission(AdmissionCode.UNAVAILABLE, workspace, user, null, null);
        }
        if (!consents.isActive(workspace, user, ConsentPurpose.COPILOT_DATA_USE)) {
            return new Admission(AdmissionCode.CONSENT_REQUIRED, workspace, user, null, null);
        }
        if (context != null) {
            contexts.verify(workspace.value(), context);
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

        QuotaUsage.Snapshot usage = quota.questions(workspace);
        if (usage.month().exhausted() || quota.monthlyInputTokensExhausted(workspace)) {
            return new Admission(AdmissionCode.QUOTA_EXCEEDED, workspace, user, null, usage.month().resetsAt());
        }
        if (usage.day().exhausted()) {
            return new Admission(AdmissionCode.QUOTA_EXCEEDED, workspace, user, null, usage.day().resetsAt());
        }
        return new Admission(AdmissionCode.ADMITTED, workspace, user, null, null);
    }
}
