package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.copilot.application.port.DataScopeReader;
import com.smarthealthfinance.copilot.application.service.CopilotAdmission;
import com.smarthealthfinance.copilot.application.service.QuotaUsage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Uso do Copilot no Workspace (ADR-0040 §8): perguntas do mês e do dia de negócio contadas no ledger com a mesma
 * regra da quota ({@link QuotaUsage}), e o recorte de dados que o Copilot enxerga. Exige só membership: não envia
 * nada ao modelo, então não exige consentimento. Os limites são provisórios até o M12.
 */
@Service
public class GetCopilotUsage {

    /** Janela de quota. */
    public record Window(long used, long limit, Instant resetsAt) {
    }

    /** Resultado; {@code dataScope} vazio quando não há dados. {@code provisional} sempre verdadeiro até o M12. */
    public record Usage(Window month, Window day, Optional<DataScopeReader.Scope> dataScope, boolean provisional) {
    }

    private final CopilotAdmission admission;
    private final QuotaUsage quota;
    private final DataScopeReader dataScope;

    public GetCopilotUsage(CopilotAdmission admission, QuotaUsage quota, DataScopeReader dataScope) {
        this.admission = admission;
        this.quota = quota;
        this.dataScope = dataScope;
    }

    /**
     * @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException sem membership
     */
    public Usage execute(UUID workspaceId) {
        CopilotAdmission.Member member = admission.requireMember(workspaceId);
        QuotaUsage.Snapshot snapshot = quota.questions(member.workspaceId());
        return new Usage(window(snapshot.month()), window(snapshot.day()), dataScope.read(workspaceId), true);
    }

    private static Window window(QuotaUsage.Window window) {
        return new Window(window.used(), window.limit(), window.resetsAt());
    }
}
