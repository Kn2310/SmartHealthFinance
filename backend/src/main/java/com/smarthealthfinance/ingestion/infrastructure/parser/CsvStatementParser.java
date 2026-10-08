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

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * CSV no modelo canônico com autodetecção (ADR-0009 §6).
 * <p>
 * Cabeçalho obrigatório com {@code data}, {@code descricao}, {@code valor} e, opcional, {@code id} (com sinônimos,
 * sem diferenciar maiúsculas/acentos, em qualquer ordem). Separador {@code ;}, {@code ,} ou tab detectado no
 * cabeçalho; aspas RFC 4180; valores na moeda da conta (BRL no MVP).
 */
@Component
public class CsvStatementParser implements StatementParser {

    private static final Currency BRL = Currency.getInstance("BRL");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT));

    private enum Column {
        DATE(true, "data", "date"),
        DESCRIPTION(true, "descricao", "description", "historico"),
        AMOUNT(true, "valor", "amount"),
        ID(false, "id", "identificador", "fitid");

        private final boolean required;
        private final List<String> names;

        Column(boolean required, String... names) {
            this.required = required;
            this.names = List.of(names);
        }

        static Optional<Column> named(String header) {
            String normalized = normalizeHeader(header);
            for (Column column : values()) {
                if (column.names.contains(normalized)) {
                    return Optional.of(column);
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public ImportFormat format() {
        return ImportFormat.CSV;
    }

    @Override
    public ParsedStatement parse(byte[] content) {
        String text = StatementText.decode(content, null);
        char delimiter = detectDelimiter(firstNonBlankLine(text));
        List<CsvRecord> records = CsvRecord.read(text, delimiter);

        CsvRecord header = records.getFirst();
        Map<Column, Integer> columns = mapColumns(header);
        List<CsvRecord> rows = records.subList(1, records.size());

        if (rows.isEmpty()) {
            throw new StatementRejectedException("NO_TRANSACTIONS");
        }
        if (rows.size() > ParsedStatement.MAX_LINES) {
            throw new StatementRejectedException("TOO_MANY_LINES");
        }

        int amountIndex = columns.get(Column.AMOUNT);
        Optional<AmountText.DecimalStyle> decimalStyle = AmountText.inferStyle(rows.stream()
                .filter(row -> amountIndex < row.fields().size())
                .map(row -> row.fields().get(amountIndex))
                .toList());

        List<StatementLine> lines = new ArrayList<>();
        List<LineIssue> issues = new ArrayList<>();
        for (CsvRecord row : rows) {
            try {
                lines.add(toLine(row, header.fields().size(), columns, decimalStyle));
            }
            catch (InvalidValueException invalid) {
                issues.add(new LineIssue(row.lineNumber(), invalid.field(), invalid.reason()));
            }
        }
        return new ParsedStatement(ImportFormat.CSV, lines, issues);
    }

    private static StatementLine toLine(CsvRecord row, int headerSize, Map<Column, Integer> columns,
                                        Optional<AmountText.DecimalStyle> decimalStyle) {
        List<String> fields = row.fields();
        // Separador sobrando no fim da linha é comum em exportações; campo extra com conteúdo não é.
        if (fields.size() < headerSize
                || fields.subList(headerSize, fields.size()).stream().anyMatch(field -> !field.isBlank())) {
            throw new InvalidValueException("line", "COLUMN_COUNT_MISMATCH");
        }

        LocalDate occurredOn = parseDate(fields.get(columns.get(Column.DATE)));
        BigDecimal amount = AmountText.parse(fields.get(columns.get(Column.AMOUNT)), decimalStyle);
        String externalId = Optional.ofNullable(columns.get(Column.ID)).map(fields::get).orElse(null);

        return StatementLine.of(row.lineNumber(), occurredOn, new Money(amount, BRL),
                fields.get(columns.get(Column.DESCRIPTION)), externalId);
    }

    private static LocalDate parseDate(String raw) {
        String value = raw.strip();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            }
            catch (DateTimeParseException ignored) {
                // tenta o próximo formato
            }
        }
        throw new InvalidValueException("occurredOn", "INVALID_DATE");
    }

    private static Map<Column, Integer> mapColumns(CsvRecord header) {
        Map<Column, Integer> columns = new EnumMap<>(Column.class);
        List<String> names = header.fields();
        for (int index = 0; index < names.size(); index++) {
            int position = index;
            Column.named(names.get(index)).ifPresent(column -> {
                if (columns.putIfAbsent(column, position) != null) {
                    throw new StatementRejectedException("DUPLICATE_COLUMN");
                }
            });
        }
        for (Column column : Column.values()) {
            if (column.required && !columns.containsKey(column)) {
                throw new StatementRejectedException("MISSING_COLUMN");
            }
        }
        return columns;
    }

    /** O separador mais frequente fora de aspas no cabeçalho; sem nenhum, {@code ;} (padrão brasileiro). */
    static char detectDelimiter(String headerLine) {
        char best = ';';
        int bestCount = 0;
        for (char candidate : new char[] { ';', ',', '\t' }) {
            int count = 0;
            boolean quoted = false;
            for (char c : headerLine.toCharArray()) {
                if (c == '"') {
                    quoted = !quoted;
                }
                else if (c == candidate && !quoted) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private static String firstNonBlankLine(String text) {
        return text.lines().filter(line -> !line.isBlank()).findFirst()
                .orElseThrow(() -> new StatementRejectedException("EMPTY_FILE"));
    }

    private static String normalizeHeader(String header) {
        String withoutAccents = Normalizer.normalize(header, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return withoutAccents.strip().toLowerCase(Locale.ROOT);
    }

    /** Registro CSV (RFC 4180) com a linha física em que começa. Linhas totalmente vazias são descartadas. */
    record CsvRecord(int lineNumber, List<String> fields) {

        static List<CsvRecord> read(String text, char delimiter) {
            List<CsvRecord> records = new ArrayList<>();
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            int line = 1;
            int recordStart = 1;

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    }
                    else if (c == '"') {
                        quoted = false;
                    }
                    else {
                        if (c == '\n') {
                            line++;
                        }
                        field.append(c);
                    }
                }
                else if (c == '"' && field.isEmpty()) {
                    quoted = true;
                }
                else if (c == delimiter) {
                    fields.add(field.toString());
                    field.setLength(0);
                }
                else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    fields.add(field.toString());
                    field.setLength(0);
                    addIfNotBlank(records, recordStart, fields);
                    fields = new ArrayList<>();
                    line++;
                    recordStart = line;
                }
                else {
                    field.append(c);
                }
            }
            if (quoted) {
                throw new StatementRejectedException("MALFORMED_CSV");
            }
            fields.add(field.toString());
            addIfNotBlank(records, recordStart, fields);

            if (records.isEmpty()) {
                throw new StatementRejectedException("EMPTY_FILE");
            }
            return records;
        }

        private static void addIfNotBlank(List<CsvRecord> records, int lineNumber, List<String> fields) {
            if (fields.stream().anyMatch(value -> !value.isBlank())) {
                records.add(new CsvRecord(lineNumber, List.copyOf(fields)));
            }
        }
    }
}
