package com.smarthealthfinance.copilot.application;

import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.domain.repository.AiUsageLedger;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Fake com a mesma semântica do adapter JDBC: janelas {@code [from, to)} sempre por Workspace. */
public final class InMemoryAiUsageLedger implements AiUsageLedger {

    private static final java.util.Set<AiOutcome> PROVIDER_FAILURES =
            java.util.EnumSet.of(AiOutcome.UNAVAILABLE, AiOutcome.TIMEOUT, AiOutcome.RATE_LIMITED);

    public final List<AiUsage> rows = new ArrayList<>();

    @Override
    public void append(AiUsage usage) {
        rows.add(usage);
    }

    @Override
    public long countQuestions(WorkspaceId workspaceId, Instant from, Instant to) {
        return rows.stream()
                .filter(row -> row.workspaceId().equals(workspaceId) && row.requestType() == AiRequestType.INTENT)
                .filter(row -> !PROVIDER_FAILURES.contains(row.outcome()))
                .filter(row -> !row.createdAt().isBefore(from) && row.createdAt().isBefore(to))
                .count();
    }

    @Override
    public java.util.Optional<java.util.Map<String, String>> findRequestVersions(WorkspaceId workspaceId,
            com.smarthealthfinance.identity.domain.valueobject.UserId userId, java.util.UUID requestId) {
        boolean found = rows.stream().anyMatch(row -> row.workspaceId().equals(workspaceId)
                && row.userId().equals(userId) && row.requestId().equals(requestId));
        return found ? java.util.Optional.of(java.util.Map.of()) : java.util.Optional.empty();
    }

    @Override
    public long sumInputTokens(WorkspaceId workspaceId, Instant from, Instant to) {
        return rows.stream()
                .filter(row -> row.workspaceId().equals(workspaceId))
                .filter(row -> !row.createdAt().isBefore(from) && row.createdAt().isBefore(to))
                .mapToLong(row -> row.tokens().promptTokens())
                .sum();
    }
}
