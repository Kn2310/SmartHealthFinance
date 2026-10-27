package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.enums.StatusReason;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.ScoredCandidate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Ciclo de vida (ADR-0021 §5, ADR-0022 §4): reconcilia os candidatos de uma geração com os insights já guardados do
 * Workspace. Puro e idempotente: reconciliar duas vezes o mesmo estado não muda nada. Nunca reescreve
 * {@code DISMISSED}, {@code EXPIRED} nem linhas de outra versão, e nunca apaga estado do usuário: um insight novo herda
 * de outra versão com o mesmo fingerprint o visto e o feedback (ADR-0028 §6), como já herdava a dispensa.
 */
public final class InsightReconciler {

    private InsightReconciler() {
    }

    /**
     * @param existing   todos os insights guardados do Workspace (qualquer versão e estado)
     * @param candidates candidatos pontuados desta geração, na versão {@code version}
     * @param periodKey  período avaliado P ({@code YYYY-MM})
     * @return só os insights novos ou alterados, para gravar
     */
    public static List<Insight> reconcile(UUID workspaceId, String version, String periodKey, List<Insight> existing,
                                          List<ScoredCandidate> candidates, Instant now, Supplier<UUID> ids) {
        return reconcile(workspaceId, version, periodKey, existing, candidates, Set.of(), now, ids);
    }

    /**
     * @param evaluatedInvoices faturas ainda em avaliação ({@code cardId|YYYY-MM}): um insight de fatura não detectado
     *                          resolve se a fatura está aqui; senão expira (ADR-0028 §5)
     */
    public static List<Insight> reconcile(UUID workspaceId, String version, String periodKey, List<Insight> existing,
                                          List<ScoredCandidate> candidates, Set<String> evaluatedInvoices,
                                          Instant now, Supplier<UUID> ids) {
        Map<String, Insight> current = new HashMap<>();
        Map<String, Insight> previousVersion = new HashMap<>();
        Set<String> dismissed = new HashSet<>();
        for (Insight insight : existing) {
            if (insight.algorithmVersion().equals(version)) {
                current.put(insight.fingerprint(), insight);
            }
            else {
                previousVersion.merge(insight.fingerprint(), insight,
                        (a, b) -> a.updatedAt().isAfter(b.updatedAt()) ? a : b);
            }
            if (insight.status() == InsightStatus.DISMISSED) {
                dismissed.add(insight.fingerprint());
            }
        }

        List<Insight> changed = new ArrayList<>();
        Set<String> detected = new HashSet<>();
        for (ScoredCandidate scored : candidates) {
            String fingerprint = scored.fingerprint();
            if (!detected.add(fingerprint) || dismissed.contains(fingerprint)) {
                continue;
            }
            Insight stored = current.get(fingerprint);
            if (stored == null) {
                changed.add(inherit(Insight.create(ids.get(), workspaceId, version, scored, now),
                        previousVersion.get(fingerprint)));
                continue;
            }
            if (stored.status() != InsightStatus.ACTIVE && stored.status() != InsightStatus.RESOLVED) {
                continue;
            }
            Insight updated = stored.withDerived(scored);
            boolean contentChanged = !updated.sameDerived(stored);
            if (contentChanged) {
                if (stored.importance() == Importance.INFORMATIVE && updated.importance() == Importance.IMPORTANT) {
                    updated = updated.withSeenAt(null);
                }
                updated = updated.revised(now);
            }
            if (stored.status() == InsightStatus.RESOLVED) {
                updated = updated.withStatus(InsightStatus.ACTIVE, null, now);
            }
            if (contentChanged || stored.status() != updated.status()) {
                changed.add(updated);
            }
        }

        for (Insight insight : existing) {
            if (insight.status() != InsightStatus.ACTIVE || detected.contains(insight.fingerprint())
                    && insight.algorithmVersion().equals(version)) {
                continue;
            }
            if (!insight.algorithmVersion().equals(version)) {
                changed.add(insight.withStatus(InsightStatus.EXPIRED, StatusReason.VERSION_SUPERSEDED, now));
            }
            else if (insight.detector().periodBound() && !periodKey.equals(insight.periodKey())) {
                changed.add(insight.withStatus(InsightStatus.EXPIRED, StatusReason.PERIOD_ENDED, now));
            }
            else if (insight.detector().invoiceBound() && !evaluatedInvoices.contains(
                    insight.subject().split("\\|")[0] + "|" + insight.periodKey())) {
                changed.add(insight.withStatus(InsightStatus.EXPIRED, StatusReason.PERIOD_ENDED, now));
            }
            else {
                changed.add(insight.withStatus(InsightStatus.RESOLVED, null, now));
            }
        }
        return changed;
    }

    /** Visto e feedback da versão anterior com o mesmo fingerprint; a dispensa já impede a criação. */
    private static Insight inherit(Insight created, Insight previous) {
        if (previous == null) {
            return created;
        }
        Insight inherited = previous.seenAt() == null ? created : created.withSeenAt(previous.seenAt());
        return previous.feedback() == null ? inherited
                : inherited.withFeedback(previous.feedback(), previous.feedbackAt());
    }
}
