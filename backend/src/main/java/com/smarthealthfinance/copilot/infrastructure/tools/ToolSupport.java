package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.cards.application.exception.CardNotFoundException;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.copilot.application.exception.TargetNotFoundException;
import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.goals.application.exception.GoalNotFoundException;
import com.smarthealthfinance.insights.application.exception.InsightNotFoundException;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Peças de texto e de valor compartilhadas pelas tools (ADR-0039 §3, §5): ordinais para distinguir itens sem dígito
 * no rótulo, tipos em pt-BR e a troca da natureza de um trecho de valores.
 */
final class ToolSupport {

    private static final List<String> ORDINALS = List.of("primeiro", "segundo", "terceiro", "quarto", "quinto",
            "sexto", "sétimo", "oitavo", "nono", "décimo");

    private ToolSupport() {
    }

    /** Ordinal em pt-BR de um item (índice a partir de zero); rótulos de valor não podem ter dígitos. */
    static String ordinal(int index) {
        return ORDINALS.get(index);
    }

    /** Tipo do objetivo em pt-BR, a partir do nome do enum {@code GoalType}. */
    static String goalType(String type) {
        return switch (type) {
            case "EMERGENCY_RESERVE" -> "reserva de emergência";
            case "PURCHASE" -> "compra";
            default -> "personalizado";
        };
    }

    /** O mesmo trecho de valores com outra natureza (ex.: contagens e textos estimados ou simulados). */
    static List<ContextValue> as(List<ContextValue> values, ValueKind kind) {
        return values.stream().map(value -> new ContextValue(value.ref(), value.label(), value.value(), value.unit(),
                kind, value.period(), value.source())).toList();
    }

    /** O pseudônimo de um cartão do catálogo da requisição, se o cartão estiver nele. */
    static Optional<String> cardPseudonym(Pseudonyms pseudonyms, UUID cardId) {
        return pseudonyms.entries().values().stream()
                .filter(entry -> entry.kind() == PseudonymKind.CARD && entry.id().equals(cardId.toString()))
                .map(Pseudonyms.Entry::pseudonym)
                .findFirst();
    }

    /**
     * Chama um caso de uso de leitura por alvo e traduz "não encontrado" (cartão, fatura, objetivo, insight) em
     * {@link TargetNotFoundException}: o pipeline responde {@code INSUFFICIENT_DATA} (ADR-0040 §9).
     */
    static <T> T lookup(Supplier<T> read) {
        try {
            return read.get();
        } catch (CardNotFoundException | InvoiceNotFoundException | GoalNotFoundException
                 | InsightNotFoundException | TransactionNotFoundException e) {
            throw new TargetNotFoundException();
        }
    }
}
