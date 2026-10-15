package com.smarthealthfinance.transactions.domain.service;

import com.smarthealthfinance.transactions.domain.valueobject.Merchant;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Descrição bruta → comerciante (ADR-0013 §1). Determinístico, sem IA, versionado: mudar qualquer regra exige uma
 * nova {@link #VERSION}, para a auditoria saber qual algoritmo produziu cada chave.
 * <p>
 * v1: tira rótulos genéricos do início ("Pix enviado - ", "Compra ", "Salário - "), parcelas, datas, códigos
 * numéricos e sufixos societários (Ltda, ME, S/A...) do fim, e junta espaços. A chave é o resultado sem acentos e
 * em minúsculas. Se nada sobrar, não há comerciante.
 * <p>
 * v2 (adendo de 2026-10-14 ao ADR-0013 §1): depois de um rótulo genérico, tira também a preposição que o liga ao
 * comerciante ("Compra do Bob" → "Bob", "Pagamento ao Fornecedor X" → "Fornecedor X"). Só logo após um rótulo e
 * só como palavra inteira: "Casa do Pão" e "Do Bob Burgers" não mudam. O artigo "a" fica de fora (ambíguo).
 */
public final class MerchantNormalizer {

    public static final String VERSION = "v2";

    /** Rótulos genéricos, já dobrados (sem acento, minúsculos), do mais longo para o mais curto. */
    private static final List<String> LEADING_LABELS = List.of(
            "transferencia recebida", "transferencia enviada", "compra no debito", "compra no credito",
            "debito automatico", "pix recebido", "pix enviado", "compra debito", "compra credito",
            "compra cartao", "pagamento", "estorno compra online", "estorno", "transferencia", "salario",
            "aluguel", "compra", "pix", "ted", "doc", "pgto");

    /** Preposições que ligam o rótulo ao comerciante, já dobradas (v2). */
    private static final List<String> LINKING_WORDS = List.of(
            "de", "do", "da", "dos", "das", "no", "na", "nos", "nas", "em", "ao", "aos", "para", "pra");

    private static final Pattern SEPARATORS = Pattern.compile("^[\\s\\-:*–—/]+");
    private static final Pattern TRAILING_NOISE = Pattern.compile(
            "[\\s\\-:*–—/#]+(?:parcela\\s+)?(?:\\d{1,2}/\\d{1,4}(?:/\\d{2,4})?|\\d{2,}|[a-z]*\\d+[a-z0-9]*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CORPORATE_SUFFIX = Pattern.compile(
            "[\\s,.-]+(?:ltda\\.?|me|epp|eireli|s/a|s\\.a\\.?|sa)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MerchantNormalizer() {
    }

    public static Optional<Merchant> normalize(String description) {
        if (description == null) {
            return Optional.empty();
        }
        String text = WHITESPACE.matcher(description.strip()).replaceAll(" ");

        text = stripLeadingLabel(text);
        text = stripTrailing(text);
        text = text.strip();

        String key = fold(text);
        if (key.isBlank() || !key.chars().anyMatch(Character::isLetter)) {
            return Optional.empty();
        }
        if (text.length() > Merchant.MAX_LENGTH) {
            text = text.substring(0, Merchant.MAX_LENGTH).strip();
            key = fold(text);
        }
        return Optional.of(new Merchant(key, displayName(text), VERSION));
    }

    /** Sem acento, minúsculas, espaços únicos: a forma comparável de um texto. */
    public static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        String plain = DIACRITICS.matcher(decomposed).replaceAll("");
        return WHITESPACE.matcher(plain.toLowerCase(Locale.ROOT).strip()).replaceAll(" ");
    }

    private static String stripLeadingLabel(String text) {
        String folded = fold(text);
        for (String label : LEADING_LABELS) {
            if (folded.startsWith(label) && (folded.length() == label.length()
                    || !Character.isLetterOrDigit(folded.charAt(label.length())))) {
                String rest = text.substring(Math.min(label.length(), text.length()));
                String stripped = SEPARATORS.matcher(rest).replaceFirst("").strip();
                // "Aluguel" ou "Compra" sozinhos são a própria descrição, não um rótulo antes do comerciante.
                return stripped.isEmpty() ? text : stripLinkingWord(stripped);
            }
        }
        return text;
    }

    /** "do Bob" → "Bob" (v2). Se a preposição for tudo o que sobrou, ela fica. */
    private static String stripLinkingWord(String text) {
        int space = text.indexOf(' ');
        if (space <= 0 || !LINKING_WORDS.contains(fold(text.substring(0, space)))) {
            return text;
        }
        String rest = text.substring(space + 1).strip();
        return rest.isEmpty() ? text : rest;
    }

    private static String stripTrailing(String text) {
        String previous;
        String current = text;
        do {
            previous = current;
            current = TRAILING_NOISE.matcher(current).replaceFirst("");
            current = CORPORATE_SUFFIX.matcher(current).replaceFirst("");
        } while (!current.equals(previous) && !current.isBlank());
        return current.isBlank() ? text : current;
    }

    /** Descrições todas em maiúsculas ou todas em minúsculas (comum em extrato) viram "Title Case". */
    private static String displayName(String text) {
        boolean allUpper = text.equals(text.toUpperCase(Locale.ROOT));
        boolean allLower = text.equals(text.toLowerCase(Locale.ROOT));
        if (!allUpper && !allLower) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean startOfWord = true;
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = !Character.isLetterOrDigit(c);
        }
        return out.toString();
    }
}
