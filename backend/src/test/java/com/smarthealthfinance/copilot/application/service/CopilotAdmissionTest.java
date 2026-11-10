package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.copilot.application.InMemoryAiUsageLedger;
import com.smarthealthfinance.copilot.application.dto.Admission;
import com.smarthealthfinance.copilot.application.port.RateLimiter;
import com.smarthealthfinance.copilot.domain.enums.AdmissionCode;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.domain.model.CopilotLimits;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Portão do Copilot (ADR-0039 §10, §13): Workspace → ligado → consentimento → rate limit → quotas. */
class CopilotAdmissionTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    /** 15/11/2026 22:30 em Brasília = 16/11 01:30 UTC: o dia de negócio ainda é 15. */
    private static final Instant NOW = Instant.parse("2026-11-16T01:30:00Z");

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
    private final InMemoryAiUsageLedger ledger = new InMemoryAiUsageLedger();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final User ana = activeUser("sub-ana");
    private final User bob = activeUser("sub-bob");
    private final Workspace anasWorkspace = personalWorkspace(ana);
    private User acting = ana;
    private boolean consentActive = true;
    private RateLimiter.Decision rate = RateLimiter.Decision.allowed();
    private final List<String> rateCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        users.store(ana);
        users.store(bob);
        workspaces.store(anasWorkspace);
        workspaces.store(personalWorkspace(bob));
    }

    private CopilotAdmission admission(CopilotLimits limits) {
        CurrentUserService current = new CurrentUserService(
                () -> new AuthenticatedIdentity(acting.externalIdentity(), acting.email().value(), null, null), users);
        return new CopilotAdmission(current, new WorkspaceAccessGuard(workspaces),
                (workspace, user, purpose) -> purpose == ConsentPurpose.COPILOT_DATA_USE && consentActive
                        && user.equals(ana.id()),
                (workspace, user, capacity, refill) -> {
                    rateCalls.add(capacity + "/" + refill);
                    return rate;
                },
                ledger, limits, Clock.fixed(NOW, ZoneOffset.UTC), meters);
    }

    private static CopilotLimits limits(int daily, int monthly, long tokens) {
        return new CopilotLimits(true, SAO_PAULO, daily, monthly, tokens, 3, Duration.ofSeconds(20));
    }

    private Admission admit(CopilotLimits limits) {
        return admission(limits).admit(anasWorkspace.id().value());
    }

    private void question(Instant at, long inputTokens) {
        ledger.append(new AiUsage(UUID.randomUUID(), anasWorkspace.id(), ana.id(), UUID.randomUUID(),
                AiRequestType.INTENT, "simulated", "simulated-v1", new TokenUsage(inputTokens, 0, 0, 0),
                BigDecimal.ZERO, "pricing-test", "", "", 1, AiOutcome.OK, at));
    }

    @Test
    void admitsWithinLimitsAndPassesTheBucketSettings() {
        Admission result = admit(limits(10, 100, 1_000_000));

        assertThat(result.code()).isEqualTo(AdmissionCode.ADMITTED);
        assertThat(result.admitted()).isTrue();
        assertThat(result.workspaceId()).isEqualTo(anasWorkspace.id());
        assertThat(result.userId()).isEqualTo(ana.id());
        assertThat(rateCalls).containsExactly("3/PT20S");
        assertThat(meters.counter("shf.copilot.admission", "code", "ADMITTED").count()).isEqualTo(1.0);
    }

    @Test
    void foreignWorkspaceIsNotFoundBeforeAnyCheck() {
        acting = bob;

        assertThatThrownBy(() -> admit(limits(10, 100, 1_000_000))).isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(rateCalls).isEmpty();
    }

    @Test
    void disabledCopilotIsUnavailableWithoutTouchingTheBucket() {
        Admission result = admission(new CopilotLimits(false, SAO_PAULO, 10, 100, 1_000_000, 3,
                Duration.ofSeconds(20))).admit(anasWorkspace.id().value());

        assertThat(result.code()).isEqualTo(AdmissionCode.UNAVAILABLE);
        assertThat(rateCalls).isEmpty();
    }

    @Test
    void withoutConsentNothingElseRuns() {
        consentActive = false;

        assertThat(admit(limits(10, 100, 1_000_000)).code()).isEqualTo(AdmissionCode.CONSENT_REQUIRED);
        assertThat(rateCalls).isEmpty();
    }

    @Test
    void rateLimitedCarriesRetryAfter() {
        rate = RateLimiter.Decision.denied(Duration.ofSeconds(7));

        Admission result = admit(limits(10, 100, 1_000_000));

        assertThat(result.code()).isEqualTo(AdmissionCode.RATE_LIMITED);
        assertThat(result.retryAfterValue()).contains(Duration.ofSeconds(7));
    }

    @Test
    void rateLimiterDownFailsClosed() {
        rate = RateLimiter.Decision.unavailable();

        assertThat(admit(limits(10, 100, 1_000_000)).code()).isEqualTo(AdmissionCode.UNAVAILABLE);
    }

    @Test
    void dailyQuotaCountsTheBusinessDayAndResetsAtLocalMidnight() {
        // 15/11 03:10 UTC = 15/11 00:10 em Brasília (conta) e 15/11 02:50 UTC = 14/11 23:50 (não conta).
        question(Instant.parse("2026-11-15T03:10:00Z"), 10);
        question(Instant.parse("2026-11-16T01:00:00Z"), 10);
        question(Instant.parse("2026-11-15T02:50:00Z"), 10);

        Admission result = admit(limits(2, 100, 1_000_000));

        assertThat(result.code()).isEqualTo(AdmissionCode.QUOTA_EXCEEDED);
        assertThat(result.resetsAtValue()).contains(Instant.parse("2026-11-16T03:00:00Z"));
        assertThat(admit(limits(3, 100, 1_000_000)).code()).isEqualTo(AdmissionCode.ADMITTED);
    }

    @Test
    void monthlyQuotaResetsOnTheFirstOfNextMonth() {
        question(Instant.parse("2026-11-01T03:00:00Z"), 10);
        question(Instant.parse("2026-11-10T12:00:00Z"), 10);
        question(Instant.parse("2026-11-01T02:59:59Z"), 10); // ainda outubro em Brasília

        Admission result = admit(limits(10, 2, 1_000_000));

        assertThat(result.code()).isEqualTo(AdmissionCode.QUOTA_EXCEEDED);
        assertThat(result.resetsAtValue()).contains(Instant.parse("2026-12-01T03:00:00Z"));
    }

    @Test
    void monthlyInputTokensQuota() {
        question(Instant.parse("2026-11-10T12:00:00Z"), 600);
        question(Instant.parse("2026-11-11T12:00:00Z"), 400);

        assertThat(admit(limits(10, 100, 1_000)).code()).isEqualTo(AdmissionCode.QUOTA_EXCEEDED);
        assertThat(admit(limits(10, 100, 1_001)).code()).isEqualTo(AdmissionCode.ADMITTED);
    }

    @Test
    void anotherWorkspaceUsageDoesNotCount() {
        Workspace bobs = workspaces.findById(personalWorkspace(bob).id()).orElseGet(() -> personalWorkspace(bob));
        ledger.append(new AiUsage(UUID.randomUUID(), bobs.id(), bob.id(), UUID.randomUUID(), AiRequestType.INTENT,
                "simulated", "simulated-v1", new TokenUsage(10, 0, 0, 0), BigDecimal.ZERO, "pricing-test", "", "", 1,
                AiOutcome.OK, Instant.parse("2026-11-15T12:00:00Z")));

        assertThat(admit(limits(1, 100, 1_000_000)).code()).isEqualTo(AdmissionCode.ADMITTED);
    }
}
