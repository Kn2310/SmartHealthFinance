package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Referências locais de uma requisição (ADR-0039 §3): contas, cartões, objetivos, insights, categorias e comerciantes
 * viram {@code conta_1}, {@code cartao_1}, {@code objetivo_1}, {@code insight_1}, {@code categoria_1},
 * {@code comerciante_1}. Ids, nomes digitados pela pessoa e nomes de comerciante (que podem ser de pessoas: Pix, TED,
 * DOC, MEI) ficam só neste mapa, em memória, durante a requisição; nada daqui é gravado nem vai ao modelo além do
 * pseudônimo e do {@code descriptor} (tipo, bandeira ou código de categoria).
 *
 * <p>Não é thread-safe: uma instância por pergunta.
 */
public final class Pseudonyms {

    /**
     * Um item pseudonimizado. {@code id} é o identificador interno (UUID ou código de catálogo), {@code descriptor} o
     * que pode ir ao modelo (ex.: {@code VISA}, {@code TRAVEL}) e {@code displayName} o nome que a pessoa vê na
     * resposta renderizada (apelido, nome do objetivo), que nunca vai ao modelo.
     */
    public record Entry(String pseudonym, PseudonymKind kind, String id, String descriptor, String displayName) {

        /** Sem id nem nome: o toString de um record exporia os dois em logs. */
        @Override
        public String toString() {
            return "Entry[" + pseudonym + "]";
        }
    }

    public static final Pattern TOKEN = Pattern.compile(
            "\\b(conta|cartao|objetivo|insight|categoria|comerciante)_([1-9]\\d{0,2})\\b");

    /** Menor nome (em letras) que conta como citado na pergunta: evita casar siglas e preposições. */
    static final int MIN_CITED_LETTERS = 3;

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final Map<String, Entry> byPseudonym = new LinkedHashMap<>();
    private final Map<String, Entry> byKindAndId = new LinkedHashMap<>();
    private final Map<PseudonymKind, Integer> counters = new LinkedHashMap<>();
    private final String foldedQuestion;

    /** Sem pergunta: nada é citado. */
    public Pseudonyms() {
        this(null);
    }

    /**
     * {@code question}: a pergunta da pessoa, só para {@link #cited} (adendo de 2026-11-16 ao ADR-0039 §3). Ela já vai
     * ao modelo como foi digitada; aqui só serve para ligar o nome citado ao pseudônimo.
     */
    public Pseudonyms(String question) {
        this.foldedQuestion = question == null ? "" : " " + fold(question) + " ";
    }

    /** Devolve o pseudônimo já dado ao item, ou dá o próximo do tipo. */
    public String assign(PseudonymKind kind, String id, String descriptor, String displayName) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(displayName, "displayName");
        String key = kind + ":" + id;
        Entry existing = byKindAndId.get(key);
        if (existing != null) {
            return existing.pseudonym();
        }
        int next = counters.merge(kind, 1, Integer::sum);
        Entry entry = new Entry(kind.prefix() + "_" + next, kind, id, descriptor, displayName);
        byPseudonym.put(entry.pseudonym(), entry);
        byKindAndId.put(key, entry);
        return entry.pseudonym();
    }

    public Optional<Entry> find(String pseudonym) {
        return Optional.ofNullable(byPseudonym.get(pseudonym));
    }

    public Optional<Entry> find(PseudonymKind kind, String pseudonym) {
        return find(pseudonym).filter(entry -> entry.kind() == kind);
    }

    public Map<String, Entry> entries() {
        return Map.copyOf(byPseudonym);
    }

    /**
     * O item foi citado na pergunta: o nome que a pessoa vê, sem acento, sem pontuação e sem diferença de caixa, aparece
     * como palavra ou frase inteira. Determinístico; nomes com menos de {@value #MIN_CITED_LETTERS} letras nunca contam.
     */
    public boolean cited(String pseudonym) {
        Entry entry = byPseudonym.get(pseudonym);
        if (entry == null || foldedQuestion.isBlank()) {
            return false;
        }
        String name = fold(entry.displayName());
        if (name.chars().filter(Character::isLetter).count() < MIN_CITED_LETTERS) {
            return false;
        }
        return foldedQuestion.contains(" " + name + " ");
    }

    /** Sem acento, minúsculas, só letras e dígitos separados por um espaço. */
    static String fold(String text) {
        String plain = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALPHANUMERIC.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** O texto sem os pseudônimos; usado para checar dígitos fora de referências. */
    public static String withoutPseudonyms(String text) {
        return TOKEN.matcher(text).replaceAll(" ");
    }

    /** Troca cada pseudônimo conhecido pelo nome que a pessoa vê; desconhecidos ficam como estão. */
    public String toDisplay(String text) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Entry entry = byPseudonym.get(matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(entry == null ? matcher.group()
                    : entry.displayName()));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
