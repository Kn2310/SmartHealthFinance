package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.application.port.CopilotCodec;
import com.smarthealthfinance.copilot.application.port.RequestCatalog;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Confere a intenção que o modelo escreveu (ADR-0039 §4): o modelo escolhe, o pipeline decide. Intenção fora do enum
 * invalida a saída; parâmetro fora do catálogo (mês futuro, categoria inexistente, alvo desconhecido, valor que não é
 * decimal positivo) vira nulo. Sem alvo, um alvo único do tipo é usado (adendo da Parte C).
 */
final class IntentResolver {

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,13}(\\.\\d{1,2})?");

    private IntentResolver() {
    }

    static Optional<IntentDecision> resolve(CopilotCodec.RawIntent raw, RequestCatalog.Catalog catalog,
                                            Pseudonyms pseudonyms, LocalDate today) {
        Optional<Intent> intent = intent(raw.intent());
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        YearMonth month = month(raw.month()).filter(m -> !m.isAfter(YearMonth.from(today))).orElse(null);
        String category = code(raw.category(), catalog);
        String target = target(intent.get(), raw.target(), pseudonyms);
        IntentDecision.Scenario scenario = intent.get() == Intent.GOAL_SIMULATION
                ? new IntentDecision.Scenario(amount(raw.monthlyAmount()),
                month(raw.deadline()).filter(m -> m.isAfter(YearMonth.from(today))).map(YearMonth::toString)
                        .orElse(null),
                code(raw.reductionCategory(), catalog), amount(raw.reductionAmount()))
                : null;
        return Optional.of(new IntentDecision(intent.get(), month, category, target, scenario));
    }

    private static Optional<Intent> intent(String value) {
        for (Intent intent : Intent.values()) {
            if (intent.name().equals(value)) {
                return Optional.of(intent);
            }
        }
        return Optional.empty();
    }

    private static Optional<YearMonth> month(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String code(String value, RequestCatalog.Catalog catalog) {
        return value != null && catalog.categoryNames().containsKey(value) ? value : null;
    }

    private static String amount(String value) {
        if (value == null || !AMOUNT.matcher(value).matches()) {
            return null;
        }
        return new BigDecimal(value).signum() > 0 ? value : null;
    }

    /** Alvo do tipo que a intenção usa; sem alvo válido, o único item do tipo, se houver só um. */
    private static String target(Intent intent, String value, Pseudonyms pseudonyms) {
        PseudonymKind kind = switch (intent) {
            case GOALS, GOAL_SIMULATION -> PseudonymKind.GOAL;
            case CARDS -> PseudonymKind.CARD;
            default -> null;
        };
        if (kind == null) {
            return null;
        }
        if (value != null && pseudonyms.find(kind, value).isPresent()) {
            return value;
        }
        var ofKind = pseudonyms.entries().values().stream().filter(entry -> entry.kind() == kind).toList();
        return ofKind.size() == 1 ? ofKind.getFirst().pseudonym() : null;
    }
}
