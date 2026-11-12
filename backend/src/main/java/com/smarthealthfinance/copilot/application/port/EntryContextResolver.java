package com.smarthealthfinance.copilot.application.port;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.domain.model.EntryContext;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;

import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Contexto de entrada de uma pergunta (ADR-0040 §3): confere que o alvo existe no Workspace e o pseudonimiza para o
 * pipeline. Implementado sobre casos de uso de leitura da lista fechada (ADR-0039 §4); nunca grava.
 */
public interface EntryContextResolver {

    /**
     * Confere o alvo antes do rate limit, para um alvo inválido não gastar quota.
     *
     * @throws com.smarthealthfinance.copilot.application.exception.ContextNotFoundException fora do Workspace
     */
    void verify(UUID workspaceId, EntryContext context);

    /**
     * Dá pseudônimo ao alvo (e ao cartão do insight de fatura, quando for o caso) e monta o que o pipeline usa.
     *
     * @throws com.smarthealthfinance.copilot.application.exception.TargetNotFoundException o alvo sumiu entre a
     *         admissão e o pipeline
     */
    Resolved resolve(UUID workspaceId, EntryContext context, Pseudonyms pseudonyms);

    /**
     * O alvo por tipo de pseudônimo, o mês fixado (só fatura) e o link da tela do contexto, usado quando o pedido é
     * uma ação que o Copilot não executa.
     */
    record Resolved(Map<PseudonymKind, String> targets, YearMonth month, OfferedAction screen) {

        public Resolved {
            Objects.requireNonNull(screen, "screen");
            targets = Map.copyOf(targets);
        }

        public Optional<String> target(PseudonymKind kind) {
            return Optional.ofNullable(targets.get(kind));
        }

        public Optional<YearMonth> monthValue() {
            return Optional.ofNullable(month);
        }

        /** Sem pseudônimos nem mês. */
        @Override
        public String toString() {
            return "Resolved[" + targets.size() + " targets]";
        }
    }
}
