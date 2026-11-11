package com.smarthealthfinance.copilot.infrastructure.persistence.adapter;

import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.domain.repository.AiUsageLedger;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import com.smarthealthfinance.identity.domain.valueobject.UserId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Usage ledger em {@code ai_usage} (ADR-0039 §10), só de inserção. É o único acesso a banco do módulo, e só à própria
 * tabela (ArchUnit). As consultas de quota usam o índice por Workspace, tipo e data.
 */
@Repository
public class JdbcAiUsageLedger implements AiUsageLedger {

    private final JdbcTemplate jdbc;

    public JdbcAiUsageLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AiUsage usage) {
        jdbc.update("""
                insert into ai_usage (id, workspace_id, user_id, request_id, request_type, provider, model,
                                      input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, cost,
                                      pricing_version, prompt_version, schema_version, latency_ms, outcome,
                                      created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, usage.id(), usage.workspaceId().value(), usage.userId().value(), usage.requestId(),
                usage.requestType().name(), usage.provider(), usage.model(), usage.tokens().input(),
                usage.tokens().output(), usage.tokens().cacheRead(), usage.tokens().cacheWrite(), usage.cost(),
                usage.pricingVersion(), usage.promptVersion(), usage.schemaVersion(), usage.latencyMs(),
                usage.outcome().name(), Timestamp.from(usage.createdAt()));
    }

    @Override
    public long countQuestions(WorkspaceId workspaceId, Instant from, Instant to) {
        Long count = jdbc.queryForObject("""
                select count(*) from ai_usage
                where workspace_id = ? and request_type = ? and created_at >= ? and created_at < ?
                  and outcome not in ('UNAVAILABLE', 'TIMEOUT', 'RATE_LIMITED')
                """, Long.class, workspaceId.value(), AiRequestType.INTENT.name(), Timestamp.from(from),
                Timestamp.from(to));
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Map<String, String>> findRequestVersions(WorkspaceId workspaceId, UserId userId, UUID requestId) {
        List<Map.Entry<String, String>> rows = jdbc.query("""
                select request_type, prompt_version from ai_usage
                where workspace_id = ? and user_id = ? and request_id = ?
                order by created_at
                """, (rs, row) -> Map.entry(rs.getString("request_type"), rs.getString("prompt_version")),
                workspaceId.value(), userId.value(), requestId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : rows) {
            if (row.getValue().isEmpty()) {
                continue;
            }
            // A regeneração usa o prompt de resposta; a última chamada de cada papel vale.
            versions.put(AiRequestType.INTENT.name().equals(row.getKey()) ? "intentPrompt" : "answerPrompt",
                    row.getValue());
        }
        return Optional.of(versions);
    }

    @Override
    public long sumInputTokens(WorkspaceId workspaceId, Instant from, Instant to) {
        Long sum = jdbc.queryForObject("""
                select coalesce(sum(input_tokens + cache_read_tokens + cache_write_tokens), 0) from ai_usage
                where workspace_id = ? and created_at >= ? and created_at < ?
                """, Long.class, workspaceId.value(), Timestamp.from(from), Timestamp.from(to));
        return sum == null ? 0 : sum;
    }
}
