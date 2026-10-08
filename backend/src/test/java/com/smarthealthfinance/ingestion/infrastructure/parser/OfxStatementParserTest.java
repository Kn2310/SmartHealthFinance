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

/** Arquivos sintéticos no formato emitido por bancos brasileiros; nenhum extrato real. */
class OfxStatementParserTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private static final String SGML_HEADER = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            """;

    private final OfxStatementParser parser = new OfxStatementParser();

    @Test
    void parsesSgmlStatementInWindows1252() {
        String ofx = SGML_HEADER + bankStatement("""
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260921120000[-3:BRT]
                <TRNAMT>-86.40
                <FITID>202609210001
                <MEMO>Compra Bistrô Lume
                </STMTTRN>
                <STMTTRN>
                <TRNTYPE>CREDIT
                <DTPOSTED>20260922
                <TRNAMT>4500.00
                <FITID>202609220001
                <NAME>Salário
                <MEMO>Empresa Aurora
                </STMTTRN>
                """);

        ParsedStatement statement = parser.parse(ofx.getBytes(StatementText.WINDOWS_1252));

        assertThat(statement.format()).isEqualTo(ImportFormat.OFX);
        assertThat(statement.issues()).isEmpty();
        assertThat(statement.lines()).containsExactly(
                new StatementLine(1, LocalDate.of(2026, 9, 21), Money.of("-86.40", BRL), "Compra Bistrô Lume",
                        "202609210001"),
                new StatementLine(2, LocalDate.of(2026, 9, 22), Money.of("4500", BRL), "Salário · Empresa Aurora",
                        "202609220001"));
    }

    @Test
    void parsesXmlStatement() {
        String ofx = """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <?OFX OFXHEADER="200" VERSION="220" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL</CURDEF>
                <BANKTRANLIST>
                <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260921</DTPOSTED><TRNAMT>-32.10</TRNAMT>
                <FITID>x1</FITID><NAME>Pão &amp; Cia</NAME><MEMO>Pão &amp; Cia</MEMO></STMTTRN>
                </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
                """;

        ParsedStatement statement = parser.parse(ofx.getBytes(StandardCharsets.UTF_8));

        // NAME e MEMO iguais não se repetem; entidades XML são decodificadas.
        assertThat(statement.lines()).singleElement().satisfies(line -> {
            assertThat(line.description()).isEqualTo("Pão & Cia");
            assertThat(line.signedAmount()).isEqualTo(Money.of("-32.10", BRL));
        });
    }

    @Test
    void acceptsCommaDecimalAndUtf8ContentDeclaredAs1252() {
        String ofx = SGML_HEADER + bankStatement("""
                <STMTTRN><DTPOSTED>20260921<TRNAMT>-12,90<FITID>t1<MEMO>Tarifa pacote serviços</STMTTRN>
                """);

        ParsedStatement statement = parser.parse(ofx.getBytes(StandardCharsets.UTF_8));

        assertThat(statement.lines()).singleElement().satisfies(line -> {
            assertThat(line.signedAmount()).isEqualTo(Money.of("-12.90", BRL));
            assertThat(line.description()).isEqualTo("Tarifa pacote serviços");
        });
    }

    @Test
    void missingFitidLeavesExternalIdEmptyForFallbackDedupe() {
        String ofx = SGML_HEADER + bankStatement("<STMTTRN><DTPOSTED>20260921<TRNAMT>-1.00<MEMO>Café</STMTTRN>");

        assertThat(parse(ofx).lines().getFirst().externalId()).isNull();
    }

    @Test
    void invalidTransactionsBecomeIssuesByPosition() {
        String ofx = SGML_HEADER + bankStatement("""
                <STMTTRN><DTPOSTED>2026<TRNAMT>-1.00<MEMO>Data curta</STMTTRN>
                <STMTTRN><DTPOSTED>20260231<TRNAMT>-1.00<MEMO>Data impossível</STMTTRN>
                <STMTTRN><DTPOSTED>20260921<MEMO>Sem valor</STMTTRN>
                <STMTTRN><DTPOSTED>20260921<TRNAMT>-1.234<MEMO>Três casas</STMTTRN>
                <STMTTRN><DTPOSTED>20260921<TRNAMT>0.00<MEMO>Zero</STMTTRN>
                <STMTTRN><DTPOSTED>20260921<TRNAMT>-2.00</STMTTRN>
                <STMTTRN><DTPOSTED>20260921<TRNAMT>-3.00<MEMO>Ok</STMTTRN>
                """);

        ParsedStatement statement = parse(ofx);

        assertThat(statement.issues()).containsExactly(
                new LineIssue(1, "occurredOn", "INVALID_DATE"),
                new LineIssue(2, "occurredOn", "INVALID_DATE"),
                new LineIssue(3, "amount", "INVALID_AMOUNT"),
                new LineIssue(4, "amount", "TOO_MANY_DECIMALS"),
                new LineIssue(5, "amount", "ZERO_AMOUNT"),
                new LineIssue(6, "description", "DESCRIPTION_REQUIRED"));
        assertThat(statement.lines()).extracting(StatementLine::lineNumber).containsExactly(7);
    }

    @Test
    void rejectsCreditCardStatement() {
        String ofx = SGML_HEADER + """
                <OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS><CURDEF>BRL
                <BANKTRANLIST><STMTTRN><DTPOSTED>20260921<TRNAMT>-1.00<MEMO>Loja</STMTTRN></BANKTRANLIST>
                </CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>
                """;

        assertRejected(ofx, "UNSUPPORTED_STATEMENT_TYPE");
    }

    @Test
    void rejectsMoreThanOneStatement() {
        String one = "<STMTRS><CURDEF>BRL<BANKTRANLIST>"
                + "<STMTTRN><DTPOSTED>20260921<TRNAMT>-1.00<MEMO>Café</STMTTRN></BANKTRANLIST></STMTRS>";

        assertRejected(SGML_HEADER + "<OFX>" + one + one + "</OFX>", "MULTIPLE_STATEMENTS");
    }

    @Test
    void rejectsOtherCurrencies() {
        String ofx = SGML_HEADER + bankStatement("<STMTTRN><DTPOSTED>20260921<TRNAMT>-1.00<MEMO>X</STMTTRN>")
                .replace("<CURDEF>BRL", "<CURDEF>USD");

        assertRejected(ofx, "UNSUPPORTED_CURRENCY");
    }

    @Test
    void rejectsStatementWithoutTransactions() {
        assertRejected(SGML_HEADER + bankStatement(""), "NO_TRANSACTIONS");
    }

    @Test
    void rejectsFilesThatAreNotOfx() {
        assertRejected("data;descricao;valor\n21/09/2026;Café;-1,00\n", "MALFORMED_OFX");
        assertRejected("<OFX><SIGNONMSGSRSV1></SIGNONMSGSRSV1></OFX>", "MALFORMED_OFX");
        assertRejected(SGML_HEADER + bankStatement("<STMTTRN><DTPOSTED>20260921<TRNAMT>-1.00<MEMO>X")
                .replace("</BANKTRANLIST></STMTRS>", ""), "MALFORMED_OFX");
    }

    @Test
    void rejectsStatementWithoutCurrency() {
        assertRejected(SGML_HEADER + bankStatement("<STMTTRN><DTPOSTED>20260921<TRNAMT>-1<MEMO>X</STMTTRN>")
                .replace("<CURDEF>BRL", ""), "MALFORMED_OFX");
    }

    @Test
    void doesNotResolveExternalEntities() {
        String ofx = """
                <?xml version="1.0"?>
                <!DOCTYPE OFX [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <OFX><STMTRS><CURDEF>BRL</CURDEF><BANKTRANLIST>
                <STMTTRN><DTPOSTED>20260921</DTPOSTED><TRNAMT>-1.00</TRNAMT><MEMO>&xxe;</MEMO></STMTTRN>
                </BANKTRANLIST></STMTRS></OFX>
                """;

        assertThat(parse(ofx).lines().getFirst().description()).isEqualTo("&xxe;");
    }

    @Test
    void detectsDeclaredCharset() {
        assertThat(OfxStatementParser.declaredCharset(SGML_HEADER.getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(StatementText.WINDOWS_1252);
        assertThat(OfxStatementParser.declaredCharset(
                "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(OfxStatementParser.declaredCharset("<OFX>".getBytes(StandardCharsets.US_ASCII))).isNull();
    }

    private static String bankStatement(String transactions) {
        return """
                <OFX>
                <BANKMSGSRSV1><STMTTRNRS><STMTRS>
                <CURDEF>BRL
                <BANKACCTFROM><BANKID>0000<ACCTID>00000-0<ACCTTYPE>CHECKING</BANKACCTFROM>
                <BANKTRANLIST><DTSTART>20260901<DTEND>20260930
                """ + transactions + """
                </BANKTRANLIST></STMTRS>
                </STMTTRNRS></BANKMSGSRSV1>
                </OFX>
                """;
    }

    private ParsedStatement parse(String ofx) {
        return parser.parse(ofx.getBytes(StandardCharsets.UTF_8));
    }

    private void assertRejected(String ofx, String reason) {
        assertThatThrownBy(() -> parse(ofx)).isInstanceOfSatisfying(StatementRejectedException.class,
                ex -> assertThat(ex.reason()).isEqualTo(reason));
    }
}
