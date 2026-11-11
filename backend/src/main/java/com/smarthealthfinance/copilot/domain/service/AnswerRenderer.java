package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.model.AnswerDraft;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renderiza uma resposta já aprovada pelo {@link ClaimValidator} (ADR-0039 §7.1): cada {@code {{v:ref}}} vira o valor
 * do contexto formatado em pt-BR (com o rótulo de estimado ou simulado) e cada pseudônimo vira o nome que a pessoa
 * conhece. Ações fora da lista oferecida pelo contexto, ou com alvo desconhecido, são descartadas (§6).
 */
public final class AnswerRenderer {

    public static final int MAX_ACTIONS = 3;

    private static final Pattern TOKENS = Pattern.compile(ClaimValidator.REFERENCE.pattern() + "|("
            + Pseudonyms.TOKEN.pattern() + ")");

    private AnswerRenderer() {
    }

    public static RenderedAnswer render(AnswerDraft draft, CopilotContext context, Pseudonyms pseudonyms) {
        AnswerType type = AnswerType.fromJson(draft.type()).orElseThrow();
        Set<String> cited = new LinkedHashSet<>(draft.financialData());
        String text = text(draft.answer(), context, pseudonyms, cited);
        List<String> explanation = draft.explanation().stream()
                .map(step -> text(step, context, pseudonyms, cited))
                .toList();
        List<RenderedAnswer.Value> values = cited.stream()
                .map(context::find)
                .flatMap(Optional::stream)
                .map(value -> value(value, context, pseudonyms))
                .toList();
        return new RenderedAnswer(type, text, explanation, values, actions(draft.actions(), context, pseudonyms));
    }

    /** Um valor do contexto como a tela mostra; usado também pelo modo seguro. */
    public static RenderedAnswer.Value value(ContextValue value, CopilotContext context, Pseudonyms pseudonyms) {
        return new RenderedAnswer.Value(value.ref(), pseudonyms.toDisplay(value.label()),
                ValueFormatter.format(value, context.currency()), value.kind(), value.period(), value.source());
    }

    /** Resolve as ações oferecidas para ids reais; usado também pelo modo seguro. */
    public static List<RenderedAnswer.Action> resolve(List<OfferedAction> offered, Pseudonyms pseudonyms) {
        List<RenderedAnswer.Action> resolved = new ArrayList<>();
        for (OfferedAction action : offered) {
            if (resolved.size() == MAX_ACTIONS) {
                break;
            }
            if (action.target() == null) {
                resolved.add(new RenderedAnswer.Action(action.kind(), null, null));
                continue;
            }
            pseudonyms.find(action.target()).ifPresent(entry ->
                    resolved.add(new RenderedAnswer.Action(action.kind(), entry.id(), entry.displayName())));
        }
        return List.copyOf(resolved);
    }

    /**
     * Uma passada só para refs e pseudônimos: o que entra na substituição (nome digitado, comerciante) nunca é
     * reinterpretado como ref ou pseudônimo.
     */
    private static String text(String raw, CopilotContext context, Pseudonyms pseudonyms, Set<String> cited) {
        Matcher matcher = TOKENS.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                ContextValue value = context.find(matcher.group(1)).orElseThrow();
                cited.add(value.ref());
                replacement = ValueFormatter.format(value, context.currency());
            } else {
                replacement = pseudonyms.find(matcher.group(2)).map(Pseudonyms.Entry::displayName)
                        .orElse(matcher.group(2));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static List<RenderedAnswer.Action> actions(List<AnswerDraft.Action> proposed, CopilotContext context,
                                                       Pseudonyms pseudonyms) {
        List<OfferedAction> accepted = new ArrayList<>();
        for (AnswerDraft.Action action : proposed) {
            kind(action.kind())
                    .map(kind -> new OfferedActionCandidate(kind, action.target()))
                    .flatMap(candidate -> context.actions().stream()
                            .filter(offered -> offered.kind() == candidate.kind()
                                    && (offered.target() == null || offered.target().equals(candidate.target())))
                            .findFirst())
                    .filter(offered -> !accepted.contains(offered))
                    .ifPresent(accepted::add);
        }
        return resolve(accepted, pseudonyms);
    }

    private record OfferedActionCandidate(ActionKind kind, String target) {}

    private static Optional<ActionKind> kind(String value) {
        for (ActionKind kind : ActionKind.values()) {
            if (kind.name().equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
