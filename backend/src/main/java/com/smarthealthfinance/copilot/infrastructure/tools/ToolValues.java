package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Peças comuns das tools (ADR-0039 §3, §5): pseudônimos dos itens e montagem de valores do contexto. Nenhum id, nome
 * digitado ou final de cartão sai daqui para o modelo: só o pseudônimo e o tipo ou a bandeira.
 */
final class ToolValues {

    private ToolValues() {
    }

    static String goal(Pseudonyms pseudonyms, GoalView goal) {
        return pseudonyms.assign(PseudonymKind.GOAL, goal.id().toString(), goal.type().name(), goal.name());
    }

    static String card(Pseudonyms pseudonyms, CardView card) {
        return pseudonyms.assign(PseudonymKind.CARD, card.id().toString(), card.brand().name(), card.nickname());
    }

    static String account(Pseudonyms pseudonyms, UUID id, String type, String name) {
        return pseudonyms.assign(PseudonymKind.ACCOUNT, id.toString(), type, name);
    }

    static String category(Pseudonyms pseudonyms, String code, String name) {
        return pseudonyms.assign(PseudonymKind.CATEGORY, code, code, name);
    }

    /** Descritor de comerciante sem categoria; o descritor nunca leva o nome. */
    static final String MERCHANT_WITHOUT_CATEGORY = "SEM_CATEGORIA";

    /**
     * Comerciante normalizado como {@code comerciante_N} (adendo de 2026-11-16 ao ADR-0039 §3): o nome pode ser de
     * pessoa (destinatário de Pix/TED/DOC, MEI) e nunca vai ao modelo. {@code key} é a chave de agrupamento (ADR-0013
     * §1), que identifica o mesmo comerciante em linhas diferentes da mesma requisição.
     */
    static String merchant(Pseudonyms pseudonyms, String key, String categoryCode, String name) {
        return pseudonyms.assign(PseudonymKind.MERCHANT, key,
                categoryCode == null ? MERCHANT_WITHOUT_CATEGORY : categoryCode, name);
    }

    /** Acumula valores na ordem de prioridade, sempre com a mesma fonte. Nulos são ignorados (dado ausente). */
    static final class Builder {

        private final String source;
        private final List<ContextValue> values = new ArrayList<>();
        /** Marcas de citação vão depois dos números: não tomam vaga do modo seguro nem mudam as refs dos valores. */
        private final List<ContextValue> citations = new ArrayList<>();

        Builder(String source) {
            this.source = source;
        }

        Builder money(String label, Money value, ValueKind kind, String period) {
            if (value != null) {
                values.add(ContextValue.draft(label, value.amount().stripTrailingZeros().toPlainString(),
                        ValueUnit.MONEY, kind, period, source));
            }
            return this;
        }

        Builder money(String label, Money value, String period) {
            return money(label, value, ValueKind.FACT, period);
        }

        /** Percentual já em pontos percentuais (12.5 = 12,5%), na escala do caso de uso. */
        Builder percent(String label, BigDecimal value, ValueKind kind, String period) {
            if (value != null) {
                values.add(ContextValue.draft(label, value.toPlainString(), ValueUnit.PERCENT, kind, period,
                        source));
            }
            return this;
        }

        Builder percent(String label, BigDecimal value, String period) {
            return percent(label, value, ValueKind.FACT, period);
        }

        Builder count(String label, long value, String period) {
            values.add(ContextValue.draft(label, Long.toString(value), ValueUnit.COUNT, ValueKind.FACT, period,
                    source));
            return this;
        }

        Builder score(String label, Integer value, ValueKind kind, String period) {
            if (value != null) {
                values.add(ContextValue.draft(label, value.toString(), ValueUnit.SCORE, kind, period, source));
            }
            return this;
        }

        Builder month(String label, YearMonth value, ValueKind kind) {
            if (value != null) {
                values.add(ContextValue.draft(label, value.toString(), ValueUnit.MONTH, kind, null, source));
            }
            return this;
        }

        Builder date(String label, LocalDate value, ValueKind kind) {
            if (value != null) {
                values.add(ContextValue.draft(label, value.toString(), ValueUnit.DATE, kind, null, source));
            }
            return this;
        }

        /** Nome de categoria do catálogo, pseudônimo ou texto fixo; texto longo é cortado no limite do valor. */
        Builder text(String label, String value, String period) {
            if (value != null && !value.isBlank()) {
                String trimmed = value.strip();
                values.add(ContextValue.draft(label, trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed,
                        ValueUnit.TEXT, ValueKind.FACT, period, source));
            }
            return this;
        }

        /**
         * Um comerciante: o valor de texto é o pseudônimo (a tela mostra o nome) e, se a pergunta cita o nome, um
         * segundo valor diz isso ao modelo, para ele ligar o nome digitado ao pseudônimo.
         */
        Builder merchant(String label, Pseudonyms pseudonyms, String pseudonym, String period) {
            text(label, pseudonym, period);
            if (pseudonyms.cited(pseudonym)) {
                citations.add(ContextValue.draft(pseudonym + " citado na pergunta", "sim", ValueUnit.TEXT,
                        ValueKind.FACT, period, source));
            }
            return this;
        }

        /** Posição do próximo valor, para o quadro de decomposição apontar para ele. */
        int size() {
            return values.size();
        }

        List<ContextValue> build() {
            List<ContextValue> all = new ArrayList<>(values);
            all.addAll(citations);
            return List.copyOf(all);
        }
    }

    static String month(YearMonth month) {
        return month == null ? null : month.toString();
    }

    static String range(LocalDate from, LocalDate to) {
        return from == null || to == null ? null : from + ".." + to;
    }

    static String range(YearMonth from, YearMonth to) {
        return from == null || to == null ? null : from + ".." + to;
    }
}
