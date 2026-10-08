package com.smarthealthfinance.ingestion.infrastructure.parser;

import com.smarthealthfinance.ingestion.application.port.StatementParser;
import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException;
import com.smarthealthfinance.ingestion.domain.model.LineIssue;
import com.smarthealthfinance.ingestion.domain.model.ParsedStatement;
import com.smarthealthfinance.ingestion.domain.model.StatementLine;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OFX 1.x (SGML) e 2.x (XML) de conta bancária (ADR-0009 §5).
 * <p>
 * Um único tokenizador de tags atende às duas versões: no SGML as folhas não fecham, mas os agregados
 * ({@code STMTRS}, {@code STMTTRN}) sim. Não usa parser XML, então não há XXE nem expansão de entidades.
 * O sinal de {@code TRNAMT} é a fonte da verdade; {@code TRNTYPE} é ignorado.
 */
@Component
public class OfxStatementParser implements StatementParser {

    private static final Currency BRL = Currency.getInstance("BRL");

    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z0-9.]+)>([^<]*)");
    private static final Pattern OFX_START = Pattern.compile("<OFX>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SGML_CHARSET = Pattern.compile("^\\s*CHARSET:\\s*(\\S+)", Pattern.MULTILINE);
    private static final Pattern XML_ENCODING = Pattern.compile("encoding=[\"']([A-Za-z0-9_.:-]+)[\"']");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(?:[xX]([0-9A-Fa-f]{1,6})|([0-9]{1,7}));");
    private static final DateTimeFormatter OFX_DATE =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    @Override
    public ImportFormat format() {
        return ImportFormat.OFX;
    }

    @Override
    public ParsedStatement parse(byte[] content) {
        String text = StatementText.decode(content, declaredCharset(content));

        Matcher start = OFX_START.matcher(text);
        if (!start.find()) {
            throw new StatementRejectedException("MALFORMED_OFX");
        }

        Statement statement = readStatement(text.substring(start.start()));

        if (statement.transactions().isEmpty()) {
            throw new StatementRejectedException("NO_TRANSACTIONS");
        }
        if (statement.transactions().size() > ParsedStatement.MAX_LINES) {
            throw new StatementRejectedException("TOO_MANY_LINES");
        }

        List<StatementLine> lines = new ArrayList<>();
        List<LineIssue> issues = new ArrayList<>();
        int position = 0;
        for (Map<String, String> transaction : statement.transactions()) {
            position++;
            try {
                lines.add(toLine(position, transaction));
            }
            catch (InvalidValueException invalid) {
                issues.add(new LineIssue(position, invalid.field(), invalid.reason()));
            }
        }
        return new ParsedStatement(ImportFormat.OFX, lines, issues);
    }

    private record Statement(List<Map<String, String>> transactions) {
    }

    private static Statement readStatement(String body) {
        List<Map<String, String>> transactions = new ArrayList<>();
        Map<String, String> current = null;
        int bankStatements = 0;
        String currency = null;

        Matcher tag = TAG.matcher(body);
        while (tag.find()) {
            boolean closing = !tag.group(1).isEmpty();
            String name = tag.group(2).toUpperCase(Locale.ROOT);
            String value = unescape(tag.group(3).strip());

            if (closing) {
                if (name.equals("STMTTRN") && current != null) {
                    transactions.add(current);
                    current = null;
                }
                continue;
            }

            switch (name) {
                case "CCSTMTRS" -> throw new StatementRejectedException("UNSUPPORTED_STATEMENT_TYPE");
                case "STMTRS" -> {
                    if (++bankStatements > 1) {
                        throw new StatementRejectedException("MULTIPLE_STATEMENTS");
                    }
                }
                case "CURDEF" -> currency = value.toUpperCase(Locale.ROOT);
                case "STMTTRN" -> {
                    if (current != null) {
                        transactions.add(current);
                    }
                    current = new HashMap<>();
                }
                default -> {
                    if (current != null && !value.isEmpty()) {
                        current.putIfAbsent(name, value);
                    }
                }
            }
        }

        if (bankStatements == 0 || current != null) {
            throw new StatementRejectedException("MALFORMED_OFX");
        }
        if (currency == null) {
            throw new StatementRejectedException("MALFORMED_OFX");
        }
        if (!currency.equals(BRL.getCurrencyCode())) {
            throw new StatementRejectedException("UNSUPPORTED_CURRENCY");
        }
        return new Statement(transactions);
    }

    private static StatementLine toLine(int position, Map<String, String> transaction) {
        LocalDate occurredOn = parseDate(transaction.get("DTPOSTED"));
        String amount = transaction.get("TRNAMT");
        if (amount == null) {
            throw new InvalidValueException("amount", "INVALID_AMOUNT");
        }
        Money signedAmount = new Money(AmountText.parseOfx(amount), BRL);

        String description = Stream.of(transaction.get("NAME"), transaction.get("MEMO"))
                .filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .distinct()
                .collect(Collectors.joining(" · "));

        return StatementLine.of(position, occurredOn, signedAmount, description, transaction.get("FITID"));
    }

    /** {@code YYYYMMDD[HHMMSS[.XXX]][[-3:BRT]]}: vale a data de negócio declarada pelo banco, sem fuso. */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.length() < 8) {
            throw new InvalidValueException("occurredOn", "INVALID_DATE");
        }
        try {
            return LocalDate.parse(raw.substring(0, 8), OFX_DATE);
        }
        catch (DateTimeParseException invalid) {
            throw new InvalidValueException("occurredOn", "INVALID_DATE");
        }
    }

    /** Charset declarado no cabeçalho SGML ({@code CHARSET:1252}) ou na declaração XML. */
    static Charset declaredCharset(byte[] content) {
        // O cabeçalho é ASCII; ler os primeiros bytes como Latin-1 nunca falha.
        String head = new String(content, 0, Math.min(content.length, 1024), StandardCharsets.ISO_8859_1);

        Matcher xml = XML_ENCODING.matcher(head);
        if (xml.find()) {
            return charsetNamed(xml.group(1));
        }
        Matcher sgml = SGML_CHARSET.matcher(head);
        if (sgml.find()) {
            return charsetNamed(sgml.group(1));
        }
        return null;
    }

    private static Charset charsetNamed(String name) {
        String normalized = name.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "1252", "WINDOWS-1252", "CP1252" -> StatementText.WINDOWS_1252;
            case "8859-1", "ISO-8859-1", "ISO8859-1", "LATIN1" -> StandardCharsets.ISO_8859_1;
            case "UTF-8", "UTF8" -> StandardCharsets.UTF_8;
            default -> null;
        };
    }

    private static String unescape(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        String named = value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&nbsp;", " ");
        Matcher numeric = NUMERIC_ENTITY.matcher(named);
        StringBuilder result = new StringBuilder();
        while (numeric.find()) {
            int codePoint = numeric.group(1) != null
                    ? Integer.parseInt(numeric.group(1), 16)
                    : Integer.parseInt(numeric.group(2));
            String replacement = Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : " ";
            numeric.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        numeric.appendTail(result);
        // &amp; por último, para "&amp;lt;" virar "&lt;" literal e não "<".
        return result.toString().replace("&amp;", "&");
    }
}
