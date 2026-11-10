package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.model.AnswerDraft;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Financial Claim Validation {@value #VERSION} (ADR-0039 §7.2). O modelo não escreve números: cita valores do
 * contexto por {@code {{v:ref}}}. A resposta reprova quando:
 * <ul>
 *   <li>cita um ref que não existe no contexto, ou um pseudônimo que não existe na requisição;</li>
 *   <li>tem caractere numérico fora de uma referência ou de um pseudônimo (lista fechada de exceções: só os
 *       pseudônimos);</li>
 *   <li>escreve número por extenso (salvo "um"/"uma", que também são artigos, e os ordinais), quantidade
 *       ("dezena", "metade", "dobro"), "por cento", "reais" ou "centavos", ou usa {@code R$}/{@code %} fora de
 *       referência (contornos do bloqueio de dígitos);</li>
 *   <li>tem referência malformada, texto vazio ou longo demais, passos demais ou tipo fora do esquema.</li>
 * </ul>
 * Não há tolerância numérica porque não há número escrito pelo modelo. Ações com tipo ou alvo desconhecido não reprovam:
 * são descartadas na renderização (§6).
 */
public final class ClaimValidator {

    public static final String VERSION = "claims-v1";
    public static final int MAX_ANSWER_CHARS = 2_000;
    public static final int MAX_STEP_CHARS = 400;
    public static final int MAX_STEPS = 6;

    public static final Pattern REFERENCE = Pattern.compile("\\{\\{v:(v[1-9]\\d{0,3})}}");
    /** Qualquer caractere numérico: dígitos, frações, sobrescritos, numerais circulados. */
    private static final Pattern DIGIT = Pattern.compile("\\p{N}");
    /**
     * Cardinais por extenso de zero em diante (exceto "um"/"uma", que são também artigos), quantidades ("dezena",
     * "metade", "dobro") e unidades de dinheiro e percentual. Ordinais ("primeiro") são permitidos.
     */
    private static final Pattern NUMBER_WORD = Pattern.compile("(?iU)\\b(zero|dois|duas|três|tres|quatro|cinco|seis"
            + "|sete|oito|nove|dez|dúzias?|duzias?|dezenas?|centenas?|milhares|metade|dobro|triplo"
            + "|onze|doze|treze|quatorze|catorze|quinze"
            + "|dezesseis|dezessete|dezoito|dezenove|vinte|trinta|quarenta|cinquenta|sessenta|setenta|oitenta|noventa"
            + "|cem|cento|duzent[oa]s|trezent[oa]s|quatrocent[oa]s|quinhent[oa]s|seiscent[oa]s|setecent[oa]s"
            + "|oitocent[oa]s|novecent[oa]s|mil|milhão|milhões|bilhão|bilhões|reais|centavos|por\\s+cento)\\b");
    private static final Pattern SYMBOL = Pattern.compile("R\\$|%");

    /** Motivo de reprovação. {@code detail} é técnico (ref ou trecho do código), nunca o texto da resposta. */
    public record Violation(String code, String detail) {}

    private ClaimValidator() {
    }

    public static List<Violation> validate(AnswerDraft draft, CopilotContext context, Pseudonyms pseudonyms) {
        Objects.requireNonNull(draft, "draft");
        List<Violation> violations = new ArrayList<>();
        if (AnswerType.fromJson(draft.type()).isEmpty()) {
            violations.add(new Violation("UNKNOWN_TYPE", "type"));
        }
        if (draft.answer().isBlank()) {
            violations.add(new Violation("EMPTY_ANSWER", "answer"));
        }
        if (draft.answer().length() > MAX_ANSWER_CHARS) {
            violations.add(new Violation("ANSWER_TOO_LONG", "answer"));
        }
        if (draft.explanation().size() > MAX_STEPS) {
            violations.add(new Violation("TOO_MANY_STEPS", "explanation"));
        }
        checkText("answer", draft.answer(), context, pseudonyms, violations);
        for (int i = 0; i < draft.explanation().size(); i++) {
            String step = draft.explanation().get(i);
            if (step.length() > MAX_STEP_CHARS) {
                violations.add(new Violation("STEP_TOO_LONG", "explanation[" + i + "]"));
            }
            checkText("explanation[" + i + "]", step, context, pseudonyms, violations);
        }
        for (String ref : draft.financialData()) {
            if (context.find(ref).isEmpty()) {
                violations.add(new Violation("UNKNOWN_REF", ref.length() <= 8 ? ref : "financialData"));
            }
        }
        return List.copyOf(violations);
    }

    private static void checkText(String field, String text, CopilotContext context, Pseudonyms pseudonyms,
                                  List<Violation> violations) {
        Matcher reference = REFERENCE.matcher(text);
        while (reference.find()) {
            if (context.find(reference.group(1)).isEmpty()) {
                violations.add(new Violation("UNKNOWN_REF", reference.group(1)));
            }
        }
        Matcher pseudonym = Pseudonyms.TOKEN.matcher(text);
        while (pseudonym.find()) {
            if (pseudonyms.find(pseudonym.group()).isEmpty()) {
                violations.add(new Violation("UNKNOWN_PSEUDONYM", pseudonym.group()));
            }
        }
        String rest = Pseudonyms.withoutPseudonyms(REFERENCE.matcher(text).replaceAll(" "));
        if (rest.contains("{{") || rest.contains("}}")) {
            violations.add(new Violation("MALFORMED_REF", field));
        }
        if (DIGIT.matcher(rest).find()) {
            violations.add(new Violation("DIGIT_OUTSIDE_REF", field));
        }
        if (NUMBER_WORD.matcher(rest).find()) {
            violations.add(new Violation("NUMBER_WORD", field));
        }
        if (SYMBOL.matcher(rest).find()) {
            violations.add(new Violation("SYMBOL_OUTSIDE_REF", field));
        }
    }
}
