package com.smarthealthfinance.ingestion.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parcela {@code k} de {@code n} informada numa linha de fatura (ADR-0009 §22 e §24). Vale só com 1 ≤ k ≤ n e
 * 2 ≤ n ≤ 48 (o máximo de parcelas do ADR-0025 §6).
 */
public record InstallmentMark(int number, int count) {

    public static final int MAX_COUNT = 48;

    /** Coluna {@code parcela}: {@code k/n} ou {@code k de n}. */
    private static final Pattern COLUMN = Pattern.compile("0*(\\d{1,2})\\s*(?:/|de)\\s*0*(\\d{1,2})",
            Pattern.CASE_INSENSITIVE);

    /** {@code PARC 03/10}, {@code PARCELA 3 DE 10}, {@code Parc.3/10}. */
    private static final Pattern LABELED = Pattern.compile(
            "\\s*[-·]?\\s*\\bPARC(?:ELA)?\\.?\\s*0*(\\d{1,2})\\s*(?:/|DE)\\s*0*(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    /** Um {@code k/n} isolado no fim da descrição. */
    private static final Pattern TRAILING = Pattern.compile("(?:^|\\s+[-·]?\\s*)0*(\\d{1,2})/0*(\\d{1,2})\\s*$");

    public InstallmentMark {
        if (!isValid(number, count)) {
            throw new InvalidValueException("installment", "INVALID_INSTALLMENT");
        }
    }

    /**
     * @return vazio para uma coluna em branco
     * @throws InvalidValueException {@code INVALID_INSTALLMENT} fora do padrão
     */
    public static Optional<InstallmentMark> parseColumn(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = COLUMN.matcher(raw.strip());
        if (!matcher.matches()) {
            throw new InvalidValueException("installment", "INVALID_INSTALLMENT");
        }
        return Optional.of(new InstallmentMark(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2))));
    }

    /** Marcador encontrado na descrição e a descrição sem ele. */
    public record Detected(InstallmentMark mark, String descriptionWithoutMark) {
    }

    /** Procura o marcador na descrição; fora de 1 ≤ k ≤ n e 2 ≤ n ≤ 48 não é parcela. */
    public static Optional<Detected> detect(String description) {
        for (Pattern pattern : new Pattern[] {LABELED, TRAILING}) {
            Matcher matcher = pattern.matcher(description);
            if (matcher.find()) {
                int number = Integer.parseInt(matcher.group(1));
                int count = Integer.parseInt(matcher.group(2));
                if (isValid(number, count)) {
                    return Optional.of(new Detected(new InstallmentMark(number, count),
                            withoutMark(description, matcher)));
                }
            }
        }
        return Optional.empty();
    }

    /** Remove o marcador de uma descrição; se nada sobrar, fica a original. */
    public static String withoutMark(String description) {
        return detect(description).map(Detected::descriptionWithoutMark).orElse(description);
    }

    private static String withoutMark(String description, Matcher matcher) {
        String rest = (description.substring(0, matcher.start()) + " " + description.substring(matcher.end()))
                .replaceAll("\\s+", " ").strip();
        rest = rest.replaceAll("^[-·\\s]+|[-·\\s]+$", "");
        return rest.isEmpty() ? description : rest;
    }

    private static boolean isValid(int number, int count) {
        return count >= 2 && count <= MAX_COUNT && number >= 1 && number <= count;
    }
}
