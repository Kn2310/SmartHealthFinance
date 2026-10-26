package com.smarthealthfinance.ingestion.infrastructure.parser;

import com.smarthealthfinance.ingestion.domain.enums.StatementKind;
import com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException;
import com.smarthealthfinance.ingestion.domain.model.LineIssue;
import com.smarthealthfinance.ingestion.domain.model.ParsedStatement;
import com.smarthealthfinance.ingestion.domain.model.StatementLine;
import com.smarthealthfinance.ingestion.domain.valueobject.InstallmentMark;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Faturas de cartão em OFX ({@code CCSTMTRS}) e CSV (ADR-0009 §21–§22). Arquivos sintéticos, nenhum real. */
class CardStatementParsingTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private static final String SGML_HEADER = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            CHARSET:1252

            """;

    private final OfxStatementParser ofx = new OfxStatementParser();
    private final CsvStatementParser csv = new CsvStatementParser();

    @Test
    void readsACreditCardStatementForACard() {
        ParsedStatement statement = ofx(cardStatement("""
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260912<TRNAMT>-86.40<FITID>A1<MEMO>Bistrô Lume</STMTTRN>
                <STMTTRN><TRNTYPE>PAYMENT<DTPOSTED>20260905<TRNAMT>3012.10<FITID>A2<MEMO>Pagamento recebido</STMTTRN>
                """, ""));

        assertThat(statement.lines()).extracting(StatementLine::signedAmount, StatementLine::bankType)
                .containsExactly(
                        tuple(Money.of("-86.40", BRL), "DEBIT"),
                        tuple(Money.of("3012.10", BRL), "PAYMENT"));
        assertThat(statement.closing()).isNull();
    }

    @Test
    void aBankStatementIsRejectedForACard() {
        String bank = SGML_HEADER + """
                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL<BANKTRANLIST>
                <STMTTRN><DTPOSTED>20260912<TRNAMT>-10.00<MEMO>Café</STMTTRN>
                </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
                """;

        assertThatThrownBy(() -> ofx.parse(bank.getBytes(StandardCharsets.UTF_8), StatementKind.CARD))
                .isInstanceOfSatisfying(StatementRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo("STATEMENT_TYPE_MISMATCH"));
    }

    @Test
    void readsTheDueDateAndMinimumPaymentOnlyWhenTheFileBringsThem() {
        ParsedStatement statement = ofx(cardStatement("""
                <STMTTRN><DTPOSTED>20260912<TRNAMT>-86.40<MEMO>Bistrô Lume</STMTTRN>
                """, """
                <CCSTMTENDTRNRS><CCSTMTENDRS><CCCLOSING>
                <FITID>F1<DTOPEN>20260828<DTCLOSE>20260928<DTPMTDUE>20261005<MINPMTDUE>511.92<BALCLOSE>-3412.80
                </CCCLOSING></CCSTMTENDRS></CCSTMTENDTRNRS>
                """));

        assertThat(statement.closing()).isEqualTo(
                new ParsedStatement.StatementClosing(LocalDate.of(2026, 10, 5), Money.of("511.92", BRL)));
    }

    @Test
    void anUnreadableMinimumIsTreatedAsMissing() {
        ParsedStatement statement = ofx(cardStatement("""
                <STMTTRN><DTPOSTED>20260912<TRNAMT>-86.40<MEMO>Bistrô Lume</STMTTRN>
                """, "<CCSTMTENDRS><CCCLOSING><DTPMTDUE>20261005<MINPMTDUE>abc</CCCLOSING></CCSTMTENDRS>"));

        assertThat(statement.closing()).isEqualTo(new ParsedStatement.StatementClosing(LocalDate.of(2026, 10, 5),
                null));
    }

    @Test
    void twoClosingsAreAmbiguousAndIgnored() {
        String closing = "<CCCLOSING><DTPMTDUE>20261005<MINPMTDUE>100.00</CCCLOSING>";
        ParsedStatement statement = ofx(cardStatement("""
                <STMTTRN><DTPOSTED>20260912<TRNAMT>-86.40<MEMO>Bistrô Lume</STMTTRN>
                """, "<CCSTMTENDRS>" + closing + closing + "</CCSTMTENDRS>"));

        assertThat(statement.closing()).isNull();
    }

    @Test
    void cardCsvUsesTheInvoiceSignAndTheInstallmentColumn() {
        ParsedStatement statement = csv("""
                data;descricao;valor;parcela
                12/07/2026;Loja Tech [exemplo] · Notebook;480,00;3/10
                12/09/2026;Bistrô Lume;86,40;
                15/09/2026;Estorno Mercado;-20,00;
                """);

        assertThat(statement.lines()).extracting(StatementLine::signedAmount).containsExactly(
                Money.of("-480.00", BRL), Money.of("-86.40", BRL), Money.of("20.00", BRL));
        assertThat(statement.lines()).extracting(StatementLine::installment)
                .containsExactly(new InstallmentMark(3, 10), null, null);
    }

    @Test
    void anInvalidInstallmentColumnRejectsOnlyThatLine() {
        ParsedStatement statement = csv("""
                data;descricao;valor;parcela
                12/07/2026;Notebook;480,00;11/10
                12/09/2026;Bistrô Lume;86,40;
                """);

        assertThat(statement.lines()).hasSize(1);
        assertThat(statement.issues()).containsExactly(new LineIssue(2, "installment", "INVALID_INSTALLMENT"));
    }

    @Test
    void anAccountCsvIgnoresTheInstallmentColumnAndKeepsItsSign() {
        ParsedStatement statement = csv.parse("""
                data;descricao;valor;parcela
                12/09/2026;Bistrô Lume;-86,40;3/10
                """.getBytes(StandardCharsets.UTF_8), StatementKind.ACCOUNT);

        assertThat(statement.lines()).singleElement().satisfies(line -> {
            assertThat(line.signedAmount()).isEqualTo(Money.of("-86.40", BRL));
            assertThat(line.installment()).isNull();
        });
    }

    private ParsedStatement ofx(String content) {
        return ofx.parse(content.getBytes(StandardCharsets.UTF_8), StatementKind.CARD);
    }

    private ParsedStatement csv(String content) {
        return csv.parse(content.getBytes(StandardCharsets.UTF_8), StatementKind.CARD);
    }

    private static String cardStatement(String transactions, String closing) {
        return SGML_HEADER + "<OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS><CURDEF>BRL<BANKTRANLIST>"
                + transactions + "</BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS>" + closing
                + "</CREDITCARDMSGSRSV1></OFX>";
    }
}
