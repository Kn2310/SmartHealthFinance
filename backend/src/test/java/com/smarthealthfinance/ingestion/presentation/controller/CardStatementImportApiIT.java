package com.smarthealthfinance.ingestion.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.BillingCycle;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Fatura de cartão importada com PostgreSQL e RabbitMQ reais (ADR-0009 §19–§30, ADR-0025 §7 e §15): o cartão Aurora
 * (fecha 28, vence 05; do dia 28 ao dia 5, fecha 15 e vence 22) com dados fictícios. As datas são relativas a hoje: o cartão é cadastrado hoje, então a fatura
 * atual é a primeira acompanhada.
 */
class CardStatementImportApiIT extends IntegrationTest {

    private static final String WS = "/api/v1/workspaces/{workspaceId}";
    private static final String IMPORTS = WS + "/imports";
    private static final String IMPORT = IMPORTS + "/{importId}";
    private static final String CARD = WS + "/cards/{cardId}";
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter OFX = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    /**
     * Ciclo em que hoje fica depois do vencimento da fatura anterior ao acompanhamento: senão o pagamento de hoje é
     * "antigo" e fica fora da alocação (ADR-0025 §15), e o teste quebraria do dia 28 ao dia 5 com o ciclo 28/5.
     */
    private final BillingCycle cycle = today.getDayOfMonth() >= 6 && today.getDayOfMonth() <= 27
            ? new BillingCycle(28, 5) : new BillingCycle(15, 22);
    /** Fatura atual do Aurora e o notebook, comprado de forma que a parcela 3 caia nela. */
    private final InvoiceDates current = new InvoiceCalendar(cycle, List.of()).invoiceOf(today);
    private final LocalDate notebookDate = current.periodStart().minusMonths(2);

    private String workspace;
    private String account;
    private String card;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        workspace = read(mvc.post().uri("/api/v1/users/me").with(ana()).exchange(), "$.workspaceId");
        account = read(post(WS + "/accounts", """
                {"name": "Conta Aurora", "type": "CHECKING"}
                """, null, workspace), "$.id");
        card = read(post(WS + "/cards", """
                {"nickname": "Aurora Platinum", "brand": "MASTERCARD", "last4": "4821",
                 "limit": {"amount": "12000.00", "currency": "BRL"}, "closingDay": %d, "dueDay": %d,
                 "defaultPaymentAccountId": "%s"}
                """.formatted(cycle.closingDay(), cycle.dueDay(), account), null, workspace), "$.id");
    }

    @Test
    void importsTheInvoiceRebuildingTheInstallmentPurchaseAndSkippingThePayment() throws Exception {
        MvcTestResult upload = uploadToCard("k-1", "fatura.csv", invoiceCsv());

        assertThat(upload).hasStatus(HttpStatus.CREATED).bodyJson()
                .hasPathSatisfying("$.cardId", v -> assertThat(v).asString().isEqualTo(card))
                .hasPathSatisfying("$.accountId", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.lines.total", v -> assertThat(v).isEqualTo(4))
                .hasPathSatisfying("$.lines.valid", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.lines.skipped", v -> assertThat(v).isEqualTo(1));
        String importId = read(upload, "$.id");

        MvcTestResult records = mvc.get().uri(IMPORT + "/records", workspace, importId).with(ana()).exchange();
        assertThat(records).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.items[0].installment.number", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.items[0].installment.count", v -> assertThat(v).isEqualTo(10))
                .hasPathSatisfying("$.items[0].direction", v -> assertThat(v).asString().isEqualTo("OUTFLOW"))
                .hasPathSatisfying("$.items[2].direction", v -> assertThat(v).asString().isEqualTo("INFLOW"))
                .hasPathSatisfying("$.items[3].status", v -> assertThat(v).asString().isEqualTo("SKIPPED"))
                .hasPathSatisfying("$.items[3].skipReason", v -> assertThat(v).asString().isEqualTo("CARD_PAYMENT"));

        confirmAndAwait(importId);
        assertThat((Integer) read(get(IMPORT, importId), "$.lines.imported")).isEqualTo(3);

        // A compra parcelada entra pelo total, na data da compra; o pagamento não vira transação.
        assertThat(jdbc.queryForList("""
                select type || ' ' || amount::text || ' ' || occurred_on::text from transactions
                where card_id = ?::uuid and source = 'IMPORT' and status = 'POSTED'""", String.class, card))
                .containsExactlyInAnyOrder("EXPENSE 4800.0000 " + notebookDate, "EXPENSE 86.4000 " + today,
                        "REFUND 20.0000 " + today);
        assertThat(count("select count(*) from transactions where type = 'TRANSFER'")).isZero();
        assertThat(jdbc.queryForObject("select description from transactions where amount = 4800",
                String.class)).isEqualTo("Loja Tech [exemplo] · Notebook");

        // Fatura atual: 86,40 + parcela 3 (480,00) − estorno 20,00. Parcelas 1 e 2 são de antes do acompanhamento.
        assertThat(get(CARD + "/invoices/current")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.invoice.amount.amount", v -> assertThat(v).asString().isEqualTo("546.40"))
                .hasPathSatisfying("$.calculationVersion", v -> assertThat(v).asString().isEqualTo("invoice-v2"));
        assertThat(get(CARD + "/utilization")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.utilized.amount", v -> assertThat(v).asString().isEqualTo("3906.40"))
                .hasPathSatisfying("$.utilizationPercent", v -> assertThat(v).isEqualTo(33))
                .hasPathSatisfying("$.composition.previousOutstanding.amount",
                        v -> assertThat(v).asString().isEqualTo("0.00"));
        assertThat(get(CARD + "/installments")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.items[0].installments[0].status",
                        v -> assertThat(v).asString().isEqualTo("BEFORE_TRACKING"))
                .hasPathSatisfying("$.items[0].installments[2].status",
                        v -> assertThat(v).asString().isEqualTo("CURRENT"));

        // O Analytics recalcula a partir do mês da compra reconstruída.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                select payload ->> 'firstDate' from outbox_events where event_type = 'import.completed'""",
                String.class)).isEqualTo(notebookDate.toString()));
    }

    @Test
    void reimportingTheSameInvoiceNeverDuplicates() throws Exception {
        confirmAndAwait(read(uploadToCard("k-1", "fatura.csv", invoiceCsv()), "$.id"));

        MvcTestResult again = uploadToCard("k-2", "fatura.csv", invoiceCsv());

        assertThat(again).hasStatus(HttpStatus.CREATED).bodyJson()
                .hasPathSatisfying("$.lines.valid", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.lines.duplicate", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.sameFileImportedBefore", v -> assertThat(v).isEqualTo(true));
    }

    @Test
    void anInstallmentOfAManualPurchaseIsReconciledInsteadOfImported() throws Exception {
        assertThat(post(CARD + "/purchases", """
                {"amount": {"amount": "4800.00", "currency": "BRL"}, "occurredOn": "%s", "description": "Notebook",
                 "installmentCount": 10}
                """.formatted(notebookDate), key(), workspace, card)).hasStatus(HttpStatus.CREATED);

        String importId = read(uploadToCard("k-1", "fatura.csv", invoiceCsv()), "$.id");
        confirmAndAwait(importId);

        assertThat(get(IMPORT, importId)).bodyJson()
                .hasPathSatisfying("$.lines.imported", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.lines.reconciled", v -> assertThat(v).isEqualTo(1));
        assertThat(count("select count(*) from transactions where amount = 4800")).isEqualTo(1);
        assertThat(count("select count(*) from card_installment_confirmations where number = 3")).isEqualTo(1);
        assertThat(mvc.get().uri(IMPORT + "/records", workspace, importId).with(ana()).exchange()).bodyJson()
                .hasPathSatisfying("$.items[0].status", v -> assertThat(v).asString().isEqualTo("RECONCILED"));
    }

    @Test
    void readsTheMinimumPaymentAndDueDateFromAnOfxThatBringsThem() throws Exception {
        String ofx = """
                OFXHEADER:100
                DATA:OFXSGML
                CHARSET:1252

                <OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS><CURDEF>BRL<BANKTRANLIST>
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>%1$s<TRNAMT>-86.40<FITID>c1<MEMO>Bistrô Lume</STMTTRN>
                </BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS>
                <CCSTMTENDTRNRS><CCSTMTENDRS><CCCLOSING><DTPMTDUE>%2$s<MINPMTDUE>511.92</CCCLOSING>
                </CCSTMTENDRS></CCSTMTENDTRNRS></CREDITCARDMSGSRSV1></OFX>
                """.formatted(today.format(OFX), current.dueDate().format(OFX));

        MvcTestResult upload = uploadToCard("k-1", "fatura.ofx", ofx);
        assertThat(upload).hasStatus(HttpStatus.CREATED).bodyJson()
                .hasPathSatisfying("$.statement.minimumPayment.amount",
                        v -> assertThat(v).asString().isEqualTo("511.92"));
        confirmAndAwait(read(upload, "$.id"));

        assertThat(get(CARD + "/invoices/current")).bodyJson()
                .hasPathSatisfying("$.invoice.minimumPayment.amount",
                        v -> assertThat(v).asString().isEqualTo("511.92"))
                .hasPathSatisfying("$.invoice.datesSource", v -> assertThat(v).asString().isEqualTo("IMPORTED"));
    }

    @Test
    void anImportedDebitBecomesTheInvoicePayment() throws Exception {
        confirmAndAwait(read(uploadToCard("k-1", "fatura.csv", invoiceCsv()), "$.id"));
        String debit = importAccountDebit("546,40");

        MvcTestResult reconciled = post(CARD + "/payments/reconcile", """
                {"transactionId": "%s"}""".formatted(debit), null, workspace, card);

        assertThat(reconciled).hasStatus(HttpStatus.CREATED).bodyJson()
                .hasPathSatisfying("$.matchedExisting", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.payment.type", v -> assertThat(v).asString().isEqualTo("TRANSFER"))
                .hasPathSatisfying("$.payment.destinationCardId", v -> assertThat(v).asString().isEqualTo(card))
                .hasPathSatisfying("$.payment.reclassifiedFromTransactionId",
                        v -> assertThat(v).asString().isEqualTo(debit));
        assertThat(jdbc.queryForObject("select status from transactions where id = ?::uuid", String.class, debit))
                .isEqualTo("REVERSED");
        assertThat(get(CARD + "/invoices/current")).bodyJson()
                .hasPathSatisfying("$.invoice.paid.amount", v -> assertThat(v).asString().isEqualTo("546.40"))
                .hasPathSatisfying("$.invoice.remaining.amount", v -> assertThat(v).asString().isEqualTo("0.00"));

        // Repetir devolve o mesmo pagamento.
        assertThat(post(CARD + "/payments/reconcile", """
                {"transactionId": "%s"}""".formatted(debit), null, workspace, card)).hasStatus(HttpStatus.OK);
        assertThat(count("select count(*) from transactions where type = 'TRANSFER'")).isEqualTo(1);
    }

    @Test
    void anImportedDebitIsLinkedToTheManualPaymentAlreadyRecorded() throws Exception {
        assertThat(post(CARD + "/payments", """
                {"accountId": "%s", "amount": {"amount": "546.40", "currency": "BRL"}, "occurredOn": "%s"}
                """.formatted(account, today), key(), workspace, card)).hasStatus(HttpStatus.CREATED);
        String debit = importAccountDebit("546,40");

        assertThat(post(CARD + "/payments/reconcile", """
                {"transactionId": "%s"}""".formatted(debit), null, workspace, card))
                .hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.matchedExisting", v -> assertThat(v).isEqualTo(true));
        assertThat(count("select count(*) from transactions where type = 'TRANSFER'")).isEqualTo(1);
        assertThat(count("select count(*) from transactions where type = 'EXPENSE' and status = 'POSTED'"))
                .isZero();
    }

    @Test
    void rejectsTwoDestinationsAndAnArchivedCard() throws Exception {
        MvcTestResult both = mvc.post().uri(IMPORTS, workspace).multipart()
                .file(new MockMultipartFile("file", "fatura.csv", "text/csv", bytes(invoiceCsv())))
                .param("accountId", account).param("cardId", card)
                .header("Idempotency-Key", key()).with(ana()).exchange();
        assertThat(both).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .hasPathSatisfying("$.details[0].field", v -> assertThat(v).asString().isEqualTo("destination"))
                .hasPathSatisfying("$.details[0].code", v -> assertThat(v).asString().isEqualTo("EXACTLY_ONE"));

        assertThat(post(CARD + "/archive", "", null, workspace, card)).hasStatusOk();
        assertThat(uploadToCard("k-9", "fatura.csv", invoiceCsv())).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo("CARD_ARCHIVED"));
    }

    /** Fatura no CSV canônico: positivo = compra, coluna {@code parcela}, e o pagamento da fatura anterior. */
    private String invoiceCsv() {
        return """
                data;descricao;valor;parcela
                %s;Loja Tech [exemplo] · Notebook;480,00;3/10
                %s;Bistrô Lume;86,40;
                %s;Estorno Mercado;-20,00;
                %s;PAGAMENTO RECEBIDO;-3012,10;
                """.formatted(notebookDate.format(BR), today.format(BR), today.format(BR), today.format(BR));
    }

    /** O débito "PAG FATURA" importado do extrato da conta; devolve o id da despesa. */
    private String importAccountDebit(String amount) throws Exception {
        MvcTestResult upload = mvc.post().uri(IMPORTS, workspace).multipart()
                .file(new MockMultipartFile("file", "extrato.csv", "text/csv", bytes("""
                        data;descricao;valor
                        %s;PAG FATURA AURORA;-%s
                        """.formatted(today.format(BR), amount))))
                .param("accountId", account)
                .header("Idempotency-Key", key()).with(ana()).exchange();
        confirmAndAwait(read(upload, "$.id"));
        return jdbc.queryForObject("""
                select id::text from transactions where account_id = ?::uuid and type = 'EXPENSE'
                  and source = 'IMPORT'""", String.class, account);
    }

    private MvcTestResult uploadToCard(String key, String fileName, String content) {
        return mvc.post().uri(IMPORTS, workspace).multipart()
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes(content)))
                .param("cardId", card)
                .header("Idempotency-Key", key)
                .with(ana())
                .exchange();
    }

    private void confirmAndAwait(String importId) {
        assertThat(mvc.post().uri(IMPORT + "/confirm", workspace, importId).with(ana()).exchange())
                .hasStatus(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat((String) read(get(IMPORT, importId), "$.status"))
                        .isEqualTo("COMPLETED"));
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri, workspace, card).with(ana()).exchange();
    }

    private MvcTestResult get(String uri, String importId) {
        return mvc.get().uri(uri, workspace, importId).with(ana()).exchange();
    }

    private MvcTestResult post(String uri, String json, String idempotencyKey, Object... vars) {
        var request = mvc.post().uri(uri, vars).with(ana()).contentType(MediaType.APPLICATION_JSON).content(json);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange();
    }

    private Integer count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(MvcTestResult result, String path) throws Exception {
        return (T) JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private static RequestPostProcessor ana() {
        return jwt().jwt(t -> t.subject("sub-ana").claim("iss", ISSUER).claim("email", "ana@example.com")
                .claim("name", "Ana"));
    }
}
