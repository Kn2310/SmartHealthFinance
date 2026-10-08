package com.smarthealthfinance.ingestion.infrastructure.parser;

import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException;
import com.smarthealthfinance.ingestion.domain.model.LineIssue;
import com.smarthealthfinance.ingestion.domain.model.ParsedStatement;
import com.smarthealthfinance.ingestion.domain.model.StatementLine;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dados sintéticos (design/specs/09-dados-de-exemplo.md); nenhum extrato real. */
class CsvStatementParserTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private final CsvStatementParser parser = new CsvStatementParser();

    @Test
    void parsesCanonicalBrazilianFile() {
        ParsedStatement statement = parse("""
                data;descricao;valor;id
                21/09/2026;Bistrô Lume;-86,40;a1
                22/09/2026;Salário;4.500,00;a2
                """);

        assertThat(statement.format()).isEqualTo(ImportFormat.CSV);
        assertThat(statement.issues()).isEmpty();
        assertThat(statement.lines()).containsExactly(
                new StatementLine(2, LocalDate.of(2026, 9, 21), Money.of("-86.40", BRL), "Bistrô Lume", "a1"),
                new StatementLine(3, LocalDate.of(2026, 9, 22), Money.of("4500", BRL), "Salário", "a2"));
        assertThat(statement.firstDate()).contains(LocalDate.of(2026, 9, 21));
        assertThat(statement.lastDate()).contains(LocalDate.of(2026, 9, 22));
    }

    @Test
    void acceptsAliasesInAnyOrderWithExtraColumnsAndCommaDelimiter() {
        ParsedStatement statement = parse("""
                Amount,Categoria,Description,Date
                -86.40,Restaurantes,Bistrô Lume,2026-09-21
                "1,234.56",Renda,"Freela, setembro",2026-09-22
                """);

        assertThat(statement.issues()).isEmpty();
        assertThat(statement.lines()).extracting(StatementLine::description)
                .containsExactly("Bistrô Lume", "Freela, setembro");
        assertThat(statement.lines().get(1).signedAmount()).isEqualTo(Money.of("1234.56", BRL));
        assertThat(statement.lines()).allSatisfy(line -> assertThat(line.externalId()).isNull());
    }

    @Test
    void acceptsAccentedAndUppercaseHeadersAndTabDelimiter() {
        ParsedStatement statement = parse("DATA\tHISTÓRICO\tVALOR\n01/09/2026\tTarifa\t-12,90\n");

        assertThat(statement.lines()).hasSize(1);
    }

    @Test
    void handlesQuotedFieldsWithEscapedQuotesAndLineBreaks() {
        ParsedStatement statement = parse("""
                data;descricao;valor
                21/09/2026;"Loja ""Sol""; centro
                segunda linha";-10,00
                22/09/2026;Café;-5,00
                """);

        assertThat(statement.lines()).extracting(StatementLine::description)
                .containsExactly("Loja \"Sol\"; centro segunda linha", "Café");
        // A linha física continua correta depois de um campo com quebra de linha.
        assertThat(statement.lines().get(1).lineNumber()).isEqualTo(4);
    }

    @Test
    void decodesWindows1252AndUtf8WithBom() {
        String csv = "data;descricao;valor\n21/09/2026;Pão de Açúcar;-32,10\n";

        ParsedStatement latin = parser.parse(csv.getBytes(StatementText.WINDOWS_1252));
        byte[] utf8 = csv.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[utf8.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, withBom, 3, utf8.length);
        ParsedStatement bom = parser.parse(withBom);

        assertThat(latin.lines().getFirst().description()).isEqualTo("Pão de Açúcar");
        assertThat(bom.lines().getFirst().description()).isEqualTo("Pão de Açúcar");
    }

    @Test
    void invalidLinesBecomeIssuesWithoutValuesAndDoNotRejectTheFile() {
        ParsedStatement statement = parse("""
                data;descricao;valor
                31/02/2026;Data impossível;-1,00
                21/09/2026;Valor ruim;abc
                21/09/2026;Zero;0,00
                21/09/2026;;-3,00
                21/09/2026;Precisão;-1,005
                21/09/2026;Faltando coluna
                21/09/2026;Ok;-7,00
                """);

        assertThat(statement.issues()).containsExactly(
                new LineIssue(2, "occurredOn", "INVALID_DATE"),
                new LineIssue(3, "amount", "INVALID_AMOUNT"),
                new LineIssue(4, "amount", "ZERO_AMOUNT"),
                new LineIssue(5, "description", "DESCRIPTION_REQUIRED"),
                new LineIssue(6, "amount", "TOO_MANY_DECIMALS"),
                new LineIssue(7, "line", "COLUMN_COUNT_MISMATCH"));
        assertThat(statement.lines()).extracting(StatementLine::lineNumber).containsExactly(8);
        assertThat(statement.totalLines()).isEqualTo(7);
    }

    @Test
    void ambiguousAmountWithoutDisambiguatingValueIsALineIssue() {
        ParsedStatement statement = parse("data;descricao;valor\n21/09/2026;Aluguel;-1.500\n");

        assertThat(statement.issues()).containsExactly(new LineIssue(2, "amount", "AMBIGUOUS_AMOUNT"));
    }

    @Test
    void ambiguousAmountFollowsOtherValuesInTheFile() {
        ParsedStatement statement = parse("data;descricao;valor\n21/09/2026;Aluguel;-1.500\n22/09/2026;Café;-5,50\n");

        assertThat(statement.lines().getFirst().signedAmount()).isEqualTo(Money.of("-1500", BRL));
    }

    @Test
    void toleratesTrailingDelimiterAndBlankLines() {
        ParsedStatement statement = parse("data;descricao;valor;\n\n21/09/2026;Café;-5,50;\n\n");

        assertThat(statement.issues()).isEmpty();
        assertThat(statement.lines()).hasSize(1);
    }

    @Test
    void rejectsFileMixingDecimalStyles() {
        assertRejected("data;descricao;valor\n21/09/2026;A;-5,50\n21/09/2026;B;-5.50\n",
                "INCONSISTENT_DECIMAL_SEPARATOR");
    }

    @Test
    void rejectsMissingRequiredColumn() {
        assertRejected("data;descricao\n21/09/2026;Café\n", "MISSING_COLUMN");
    }

    @Test
    void rejectsDuplicatedColumn() {
        assertRejected("data;descricao;valor;amount\n21/09/2026;Café;-1;-1\n", "DUPLICATE_COLUMN");
    }

    @Test
    void rejectsHeaderOnly() {
        assertRejected("data;descricao;valor\n", "NO_TRANSACTIONS");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> parser.parse(new byte[0])).isInstanceOfSatisfying(StatementRejectedException.class,
                ex -> assertThat(ex.reason()).isEqualTo("EMPTY_FILE"));
        assertRejected(" \n \n", "EMPTY_FILE");
    }

    @Test
    void rejectsUnterminatedQuote() {
        assertRejected("data;descricao;valor\n21/09/2026;\"Café;-1\n", "MALFORMED_CSV");
    }

    @Test
    void rejectsBinaryContent() {
        byte[] zip = { 'P', 'K', 3, 4, 0, 0, 0, 1 };

        assertThatThrownBy(() -> parser.parse(zip)).isInstanceOfSatisfying(StatementRejectedException.class,
                ex -> assertThat(ex.reason()).isEqualTo("UNREADABLE_FILE"));
    }

    @Test
    void rejectsMoreLinesThanTheLimit() {
        StringBuilder csv = new StringBuilder("data;descricao;valor\n");
        csv.append("21/09/2026;Café;-1,00\n".repeat(ParsedStatement.MAX_LINES + 1));

        assertRejected(csv.toString(), "TOO_MANY_LINES");
    }

    @Test
    void acceptsExactlyTheLineLimit() {
        StringBuilder csv = new StringBuilder("data;descricao;valor\n");
        csv.append("21/09/2026;Café;-1,00\n".repeat(ParsedStatement.MAX_LINES));

        assertThat(parse(csv.toString()).lines()).hasSize(ParsedStatement.MAX_LINES);
    }

    private ParsedStatement parse(String csv) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    private void assertRejected(String csv, String reason) {
        assertThatThrownBy(() -> parse(csv)).isInstanceOfSatisfying(StatementRejectedException.class,
                ex -> assertThat(ex.reason()).isEqualTo(reason));
    }
}
