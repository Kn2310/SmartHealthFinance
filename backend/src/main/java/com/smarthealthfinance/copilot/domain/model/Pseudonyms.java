package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Referências locais de uma requisição (ADR-0039 §3): contas, cartões, objetivos, insights e categorias viram
 * {@code conta_1}, {@code cartao_1}, {@code objetivo_1}, {@code insight_1}, {@code categoria_1}. Ids e nomes digitados
 * pela pessoa ficam só neste mapa, em memória, durante a requisição; nada daqui é gravado nem vai ao modelo além do
 * pseudônimo e do {@code descriptor} (tipo ou bandeira).
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

    public static final Pattern TOKEN = Pattern.compile("\\b(conta|cartao|objetivo|insight|categoria)_([1-9]\\d{0,2})\\b");

    private final Map<String, Entry> byPseudonym = new LinkedHashMap<>();
    private final Map<String, Entry> byKindAndId = new LinkedHashMap<>();
    private final Map<PseudonymKind, Integer> counters = new LinkedHashMap<>();

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
