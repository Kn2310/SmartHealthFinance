package com.smarthealthfinance.copilot.application.port;

import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.SourceRef;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tool somente leitura de uma intenção (ADR-0039 §4): adapter fino sobre os casos de uso da lista fechada, que já
 * autorizam o membro. Recebe o {@code workspaceId} do pipeline, nunca do modelo, e devolve valores minimizados, com
 * pseudônimos no lugar de ids e nomes digitados (§3). Não grava dado financeiro.
 */
public interface FinancialTool {

    Intent intent();

    Result collect(Request request);

    /**
     * Entrada de uma tool. {@code categoryNames} é o catálogo do sistema (código → nome pt-BR); {@code pseudonyms} é o
     * mapa da requisição, já com os alvos do catálogo da intenção.
     */
    record Request(UUID workspaceId, IntentDecision decision, Pseudonyms pseudonyms, LocalDate today,
                   Map<String, String> categoryNames) {

        public Request {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(pseudonyms, "pseudonyms");
            Objects.requireNonNull(today, "today");
            categoryNames = Map.copyOf(categoryNames);
        }

        public String categoryName(String code) {
            return categoryNames.getOrDefault(code, "Sem categoria");
        }

        /** Sem a decisão nem o mapa de pseudônimos (nomes digitados). */
        @Override
        public String toString() {
            return "Request[" + decision + "]";
        }
    }

    /**
     * Situação dos dados da intenção (ADR-0039 §5): {@code NO_DATA} e {@code NOT_BUILT} viram
     * {@code INSUFFICIENT_DATA} sem chamar o modelo; {@code NEEDS_TARGET} pede para a pessoa escolher o item.
     */
    enum Status { DATA, NO_DATA, NOT_BUILT, NEEDS_TARGET }

    /** Valores em ordem de prioridade (o corte por teto de contexto tira do fim); refs ainda não numeradas. */
    record Result(Status status, List<ContextValue> values, DataQuality quality, List<SourceRef> sources,
                  List<OfferedAction> actions) {

        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(quality, "quality");
            values = List.copyOf(values);
            sources = List.copyOf(sources);
            actions = List.copyOf(actions);
        }

        public static Result of(Status status) {
            return new Result(status, List.of(), DataQuality.EMPTY, List.of(), List.of());
        }

        /** Sem os valores. */
        @Override
        public String toString() {
            return "Result[" + status + ", " + values.size() + " values]";
        }
    }
}
