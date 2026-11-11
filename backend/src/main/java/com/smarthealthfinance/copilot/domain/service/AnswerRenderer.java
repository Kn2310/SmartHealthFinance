package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.model.AnswerDraft;
import com.smarthealthfinance.copilot.domain.model.AnswerSegment;
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
 * conhece. Ações fora da lista oferecida pelo contexto, ou com alvo desconhecido, são descartadas (§6). Na mesma
 * passada, o texto também sai como segmentos tipados (ADR-0040 §3): o valor vira {@code VALUE{ref}} e o pseudônimo
 * vira texto com o nome que a pessoa conhece.
 */
public final class AnswerRenderer {

    public static final int MAX_ACTIONS = 3;

    private static final Pattern TOKENS = Pattern.compile(ClaimValidator.REFERENCE.pattern() + "|("
            + Pseudonyms.TOKEN.pattern() + ")");

    private AnswerRenderer() {
    }

    /** Um texto renderizado: plano e em segmentos. */
    public record Rendered(String text, List<AnswerSegment> segments) {
    }

    public static RenderedAnswer render(AnswerDraft draft, CopilotContext context, Pseudonyms pseudonyms) {
        AnswerType type = AnswerType.fromJson(draft.type()).orElseThrow();
        Set<String> cited = new LinkedHashSet<>(draft.financialData());
        Rendered answer = text(draft.answer(), context, pseudonyms, cited);
        List<Rendered> steps = draft.explanation().stream()
                .map(step -> text(step, context, pseudonyms, cited))
                .toList();
        List<RenderedAnswer.Value> values = cited.stream()
                .map(context::find)
                .flatMap(Optional::stream)
                .map(value -> value(value, context, pseudonyms))
                .toList();
        return new RenderedAnswer(type, answer.text(), answer.segments(),
                steps.stream().map(Rendered::text).toList(), steps.stream().map(Rendered::segments).toList(), values,
                actions(draft.actions(), context, pseudonyms), null, List.of());
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
                resolved.add(new RenderedAnswer.Action(action.kind(), null, null, action.params()));
                continue;
            }
            pseudonyms.find(action.target()).ifPresent(entry -> resolved.add(
                    new RenderedAnswer.Action(action.kind(), entry.id(), entry.displayName(), action.params())));
        }
        return List.copyOf(resolved);
    }

    /**
     * Uma passada só para refs e pseudônimos: o que entra na substituição (nome digitado, comerciante) nunca é
     * reinterpretado como ref ou pseudônimo. Texto vizinho vira um único segmento.
     */
    private static Rendered text(String raw, CopilotContext context, Pseudonyms pseudonyms, Set<String> cited) {
        Matcher matcher = TOKENS.matcher(raw);
        StringBuilder plain = new StringBuilder();
        Segments segments = new Segments();
        int last = 0;
        while (matcher.find()) {
            segments.text(raw.substring(last, matcher.start()));
            plain.append(raw, last, matcher.start());
            last = matcher.end();
            if (matcher.group(1) != null) {
                ContextValue value = context.find(matcher.group(1)).orElseThrow();
                cited.add(value.ref());
                plain.append(ValueFormatter.format(value, context.currency()));
                segments.value(value.ref());
            } else {
                String name = pseudonyms.find(matcher.group(2)).map(Pseudonyms.Entry::displayName)
                        .orElse(matcher.group(2));
                plain.append(name);
                segments.text(name);
            }
        }
        segments.text(raw.substring(last));
        plain.append(raw, last, raw.length());
        return new Rendered(plain.toString(), segments.build());
    }

    /** Acumula segmentos juntando textos vizinhos e descartando vazios. */
    public static final class Segments {

        private final List<AnswerSegment> list = new ArrayList<>();
        private final StringBuilder pending = new StringBuilder();

        public Segments text(String value) {
            pending.append(value);
            return this;
        }

        public Segments value(String ref) {
            flush();
            list.add(new AnswerSegment.Value(ref));
            return this;
        }

        public List<AnswerSegment> build() {
            flush();
            return List.copyOf(list);
        }

        private void flush() {
            if (!pending.isEmpty()) {
                list.add(new AnswerSegment.Text(pending.toString()));
                pending.setLength(0);
            }
        }
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
