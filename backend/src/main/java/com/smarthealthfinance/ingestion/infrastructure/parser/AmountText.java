package com.smarthealthfinance.ingestion.infrastructure.parser;

import com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException;
import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Interpreta valores monetários escritos por bancos (ADR-0009 §6), sempre em {@link BigDecimal}, sem arredondar.
 * <p>
 * O separador decimal é decidido por arquivo: valores inequívocos (dois separadores diferentes, um separador
 * repetido, ou um separador seguido de 1–2 dígitos) definem o estilo; {@code 1.234} / {@code 1,234} sozinhos são
 * ambíguos e dependem dos demais valores do arquivo.
 */
final class AmountText {

    enum DecimalStyle {
        /** {@code 1.234,56} (pt-BR). */
        COMMA,
        /** {@code 1,234.56}. */
        DOT
    }

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]");
    private static final Pattern NON_BODY = Pattern.compile("[^0-9.,]");
    private static final Pattern INTEGER = Pattern.compile("\\d+");
    private static final Pattern COMMA_STYLE = Pattern.compile("\\d{1,3}(\\.\\d{3})+(,\\d+)?|\\d+(,\\d+)?");
    private static final Pattern DOT_STYLE = Pattern.compile("\\d{1,3}(,\\d{3})+(\\.\\d+)?|\\d+(\\.\\d+)?");
    private static final Pattern OFX_STYLE = Pattern.compile("\\d+([.,]\\d+)?|[.,]\\d+");

    private AmountText() {
    }

    /**
     * Estilo decimal do arquivo, a partir dos valores inequívocos. Valores inválidos são ignorados aqui e recusados
     * linha a linha depois.
     *
     * @throws StatementRejectedException {@code INCONSISTENT_DECIMAL_SEPARATOR} se o arquivo mistura estilos
     */
    static Optional<DecimalStyle> inferStyle(Collection<String> rawValues) {
        Set<DecimalStyle> styles = EnumSet.noneOf(DecimalStyle.class);
        for (String raw : rawValues) {
            Signed signed = split(raw);
            if (signed != null) {
                styleOf(signed.body()).ifPresent(styles::add);
            }
        }
        if (styles.size() > 1) {
            throw new StatementRejectedException("INCONSISTENT_DECIMAL_SEPARATOR");
        }
        return styles.stream().findFirst();
    }

    /**
     * Valor com sinal de uma linha de CSV.
     *
     * @throws InvalidValueException {@code INVALID_AMOUNT} ou {@code AMBIGUOUS_AMOUNT}
     */
    static BigDecimal parse(String raw, Optional<DecimalStyle> fileStyle) {
        Signed signed = split(raw);
        if (signed == null) {
            throw new InvalidValueException("amount", "INVALID_AMOUNT");
        }
        String body = signed.body();

        if (INTEGER.matcher(body).matches()) {
            return signed.apply(new BigDecimal(body));
        }

        DecimalStyle style = styleOf(body).or(() -> fileStyle)
                .orElseThrow(() -> new InvalidValueException("amount", "AMBIGUOUS_AMOUNT"));

        Pattern shape = style == DecimalStyle.COMMA ? COMMA_STYLE : DOT_STYLE;
        if (!shape.matcher(body).matches()) {
            throw new InvalidValueException("amount", "INVALID_AMOUNT");
        }

        String plain = style == DecimalStyle.COMMA
                ? body.replace(".", "").replace(',', '.')
                : body.replace(",", "");
        return signed.apply(new BigDecimal(plain));
    }

    /**
     * Valor do {@code TRNAMT} do OFX: sem separador de milhar; o decimal é ponto pela especificação, mas há bancos
     * que emitem vírgula.
     */
    static BigDecimal parseOfx(String raw) {
        Signed signed = split(raw);
        if (signed == null || !OFX_STYLE.matcher(signed.body()).matches()) {
            throw new InvalidValueException("amount", "INVALID_AMOUNT");
        }
        return signed.apply(new BigDecimal(signed.body().replace(',', '.')));
    }

    private static Optional<DecimalStyle> styleOf(String body) {
        int lastDot = body.lastIndexOf('.');
        int lastComma = body.lastIndexOf(',');

        if (lastDot >= 0 && lastComma >= 0) {
            return Optional.of(lastComma > lastDot ? DecimalStyle.COMMA : DecimalStyle.DOT);
        }
        if (lastDot < 0 && lastComma < 0) {
            return Optional.empty();
        }

        char separator = lastDot >= 0 ? '.' : ',';
        int last = Math.max(lastDot, lastComma);

        // "1.234.567" só pode ser milhar: o decimal, se houvesse, seria o outro separador.
        if (body.indexOf(separator) != last) {
            return Optional.of(separator == '.' ? DecimalStyle.COMMA : DecimalStyle.DOT);
        }
        // Um separador seguido de 3 dígitos pode ser milhar ou decimal: ambíguo.
        if (body.length() - last - 1 == 3) {
            return Optional.empty();
        }
        return Optional.of(separator == ',' ? DecimalStyle.COMMA : DecimalStyle.DOT);
    }

    /** Remove "R$", espaços e o sinal ("-" antes ou depois, "+" antes). Devolve null se sobrar lixo. */
    private static Signed split(String raw) {
        if (raw == null) {
            return null;
        }
        String value = WHITESPACE.matcher(raw.replace("R$", "")).replaceAll("");
        boolean negative = false;

        if (value.startsWith("-") || value.startsWith("+")) {
            negative = value.charAt(0) == '-';
            value = value.substring(1);
        }
        else if (value.endsWith("-")) {
            negative = true;
            value = value.substring(0, value.length() - 1);
        }

        if (value.isEmpty() || NON_BODY.matcher(value).find()) {
            return null;
        }
        return new Signed(negative, value);
    }

    private record Signed(boolean negative, String body) {
        BigDecimal apply(BigDecimal magnitude) {
            return negative ? magnitude.negate() : magnitude;
        }
    }
}
