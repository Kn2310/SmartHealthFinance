package com.smarthealthfinance.cards.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Lançamentos de cartão pela API (ADR-0026 §10) e o efeito no Overview (ADR-0006 §17): a compra conta uma vez como
 * despesa na data dela, o pagamento de fatura é transferência e só ele move o saldo das contas.
 */
@ExtendWith(OutputCaptureExtension.class)
class CardTransactionsApiIT extends IntegrationTest {

    private static final String WS = "/api/v1/workspaces/{workspaceId}";
    private static final String CARD = WS + "/cards/{cardId}";

    @Autowired
    JdbcTemplate jdbc;

    private String workspace;
    private String account;
    private String card;
    private final String today = LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString();

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        workspace = read(mvc.post().uri("/api/v1/users/me").with(ana()).exchange(), "$.workspaceId");
        account = read(post(WS + "/accounts", """
                {"name": "Conta Aurora", "type": "CHECKING"}
                """, null, workspace), "$.id");
        assertThat(post(WS + "/transactions", """
                {"type": "ADJUSTMENT", "accountId": "%s", "adjustmentDirection": "INCREASE",
                 "amount": {"amount": "5000.00", "currency": "BRL"}, "occurredOn": "%s", "description": "Saldo inicial"}
                """.formatted(account, today), key(), workspace)).hasStatus(HttpStatus.CREATED);
        card = read(post(WS + "/cards", """
                {"nickname": "Aurora Platinum", "brand": "MASTERCARD", "last4": "4821",
                 "limit": {"amount": "12000.00", "currency": "BRL"}, "closingDay": 25, "dueDay": 5,
                 "defaultPaymentAccountId": "%s"}
                """.formatted(account), null, workspace), "$.id");
        // Histórico acompanhado desde muito antes (como depois de importar faturas antigas): um pagamento de hoje nunca
        // cai no corte dos pagamentos anteriores ao acompanhamento (ADR-0025 §15), qualquer que seja a data do teste.
        jdbc.update("update credit_cards set tracking_start_month = date '2000-01-01' where id = ?::uuid", card);
    }

    @Test
    void installmentPurchaseAndPaymentAreCountedOnceAndOnlyThePaymentMovesTheBalance() throws Exception {
        String purchaseKey = key();
        MvcTestResult purchase = purchase(purchaseKey, "300.00", 3);
        assertThat(purchase).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.transaction.type", t -> assertThat(t).asString().isEqualTo("EXPENSE"))
                .hasPathSatisfying("$.transaction.cardId", c -> assertThat(c).asString().isEqualTo(card))
                .hasPathSatisfying("$.installments.length()", n -> assertThat(n).asNumber().isEqualTo(3))
                .hasPathSatisfying("$.installments[0].amount.amount", a -> assertThat(a).asString().isEqualTo("100.00"))
                .hasPathSatisfying("$.calculationVersion", v -> assertThat(v).asString().isEqualTo("invoice-v2"));
        Object accountId = read(purchase, "$.transaction.accountId");
        assertThat(accountId).isNull();
        String purchaseId = read(purchase, "$.transaction.id");

        // Replay devolve a mesma compra; a mesma chave com outro número de parcelas é recusada.
        assertThat(purchase(purchaseKey, "300.00", 3)).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.transaction.id", id -> assertThat(id).asString().isEqualTo(purchaseId));
        assertThat(purchase(purchaseKey, "300.00", 2)).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY).bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("select count(*) from card_installments")).isEqualTo(3);

        // Depois da compra: despesa de 300 no mês, saldo intacto.
        assertOverview("5000.00", "300.00", 0);

        MvcTestResult payment = post(CARD + "/payments", """
                {"accountId": "%s", "amount": {"amount": "100.00", "currency": "BRL"}, "occurredOn": "%s"}
                """.formatted(account, today), key(), workspace, card);
        assertThat(payment).hasStatus(HttpStatus.CREATED).bodyJson()
                .hasPathSatisfying("$.transaction.type", t -> assertThat(t).asString().isEqualTo("TRANSFER"))
                .hasPathSatisfying("$.transaction.destinationCardId", c -> assertThat(c).asString().isEqualTo(card))
                .hasPathSatisfying("$.transaction.description",
                        d -> assertThat(d).asString().isEqualTo("Pagamento de fatura"));

        // Sem dupla contagem: a despesa continua 300; o pagamento só tira 100 do saldo e é transferência.
        assertOverview("4900.00", "300.00", 1);

        assertThat(get(CARD + "/invoices/current")).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.invoice.charges.amount", a -> assertThat(a).asString().isEqualTo("100.00"))
                .hasPathSatisfying("$.invoice.paid.amount", a -> assertThat(a).asString().isEqualTo("100.00"))
                .hasPathSatisfying("$.invoice.status", s -> assertThat(s).asString().isEqualTo("OPEN"));
        assertThat(get(CARD + "/utilization")).bodyJson()
                .hasPathSatisfying("$.utilized.amount", a -> assertThat(a).asString().isEqualTo("200.00"));
        assertThat(get(CARD + "/installments")).bodyJson()
                .hasPathSatisfying("$.items.length()", n -> assertThat(n).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.items[0].currentNumber", n -> assertThat(n).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.items[0].status", s -> assertThat(s).asString().isEqualTo("ACTIVE"));
        assertThat(get(CARD + "/installments/" + purchaseId)).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.remainingAfterCurrent.amount",
                        a -> assertThat(a).asString().isEqualTo("200.00"));

        assertThat(mvc.get().uri(WS + "/transactions", workspace).param("cardId", card).with(ana())).bodyJson()
                .hasPathSatisfying("$.totalItems", n -> assertThat(n).asNumber().isEqualTo(2));
        assertThat(mvc.get().uri(WS + "/transactions/summary", workspace).param("cardId", card).with(ana()))
                .bodyJson()
                .hasPathSatisfying("$.expense.amount", a -> assertThat(a).asString().isEqualTo("300.00"));

        // Corrigir a compra por estorno do lançamento tira a compra da fatura e da despesa.
        assertThat(mvc.post().uri(WS + "/transactions/{id}/reverse", workspace, purchaseId).with(ana()))
                .hasStatus(HttpStatus.OK);
        assertOverview("4900.00", "0.00", 1);
        assertThat(get(CARD + "/invoices/current")).bodyJson()
                .hasPathSatisfying("$.invoice.charges.amount", a -> assertThat(a).asString().isEqualTo("0.00"));
    }

    @Test
    void creditOfAnInstallmentPurchaseCancelsFutureInstallments() throws Exception {
        String purchaseId = read(purchase(key(), "300.00", 3), "$.transaction.id");

        MvcTestResult credit = post(CARD + "/credits", """
                {"amount": {"amount": "300.00", "currency": "BRL"}, "occurredOn": "%s", "description": "Estorno",
                 "refundOfTransactionId": "%s"}
                """.formatted(today, purchaseId), key(), workspace, card);
        assertThat(credit).hasStatus(HttpStatus.CREATED).bodyJson()
                .hasPathSatisfying("$.transaction.type", t -> assertThat(t).asString().isEqualTo("REFUND"));

        assertThat(get(CARD + "/invoices/future")).bodyJson()
                .hasPathSatisfying("$.totalCommitted.amount", a -> assertThat(a).asString().isEqualTo("0.00"));
        assertThat(get(CARD + "/utilization")).bodyJson()
                .hasPathSatisfying("$.utilized.amount", a -> assertThat(a).asString().isEqualTo("0.00"));
        assertThat(mvc.get().uri(WS + "/overview", workspace).with(ana())).bodyJson()
                .hasPathSatisfying("$.cashFlow.refunds.amount", a -> assertThat(a).asString().isEqualTo("300.00"));

        // Um estorno não passa da compra, e só vale para compra do mesmo cartão.
        assertThat(post(CARD + "/credits", """
                {"amount": {"amount": "0.01", "currency": "BRL"}, "occurredOn": "%s", "description": "Estorno",
                 "refundOfTransactionId": "%s"}
                """.formatted(today, purchaseId), key(), workspace, card)).hasStatus(HttpStatus.BAD_REQUEST);
        String other = read(post(WS + "/cards", """
                {"nickname": "Outro", "brand": "VISA", "last4": "0937",
                 "limit": {"amount": "1000.00", "currency": "BRL"}, "closingDay": 5, "dueDay": 12}
                """, null, workspace), "$.id");
        assertThat(post(CARD + "/credits", """
                {"amount": {"amount": "1.00", "currency": "BRL"}, "occurredOn": "%s", "description": "Estorno",
                 "refundOfTransactionId": "%s"}
                """.formatted(today, purchaseId), key(), workspace, other)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.details[0].code", c -> assertThat(c).asString().isEqualTo("NOT_SAME_CARD"));
    }

    @Test
    void validatesPurchasesAndRefusesArchivedCards() throws Exception {
        assertThat(purchase(key(), "300.00", 49)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().isEqualTo("installmentCount"));
        assertThat(purchase(key(), "0.02", 3)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .hasPathSatisfying("$.details[0].code",
                        c -> assertThat(c).asString().isEqualTo("TOO_MANY_FOR_AMOUNT"));
        assertThat(purchase(null, "10.00", 1)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(count("select count(*) from transactions where card_id is not null")).isZero();

        assertThat(mvc.post().uri(CARD + "/archive", workspace, card).with(ana())).hasStatus(HttpStatus.OK);
        assertThat(purchase(key(), "10.00", 1)).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("CARD_ARCHIVED"));
    }

    @Test
    void logsOnlyIds(CapturedOutput output) throws Exception {
        purchase(key(), "987.65", 2);

        assertThat(output).contains("Card purchase recorded").doesNotContain("987.65", "Compra secreta");
    }

    @Test
    void overviewShowsTheInvoicesToPayApartFromTheBalance() throws Exception {
        assertThat(purchase(key(), "300.00", 3)).hasStatus(HttpStatus.CREATED);
        MvcTestResult current = get(CARD + "/invoices/current");
        String closingDate = read(current, "$.closingDate");
        String dueDate = read(current, "$.dueDate");
        MvcTestResult summary = mvc.get().uri(WS + "/cards/summary", workspace).with(ana()).exchange();

        // ADR-0006 §18: a dívida fica em "cards", o saldo total continua só das contas, e o bloco não depende do
        // período pedido.
        for (String period : new String[] {"", "?period=PREVIOUS_MONTH"}) {
            assertThat(mvc.get().uri(WS + "/overview" + period, workspace).with(ana())).hasStatus(HttpStatus.OK)
                    .bodyJson()
                    .hasPathSatisfying("$.summary.totalBalance.amount", a -> assertThat(a).asString()
                            .isEqualTo("5000.00"))
                    .hasPathSatisfying("$.cards.activeCards", n -> assertThat(n).asNumber().isEqualTo(1))
                    .hasPathSatisfying("$.cards.openInvoices.amount", a -> assertThat(a).asString()
                            .isEqualTo(read(summary, "$.openInvoices.amount").toString()).isEqualTo("100.00"))
                    .hasPathSatisfying("$.cards.nextDue.date", d -> assertThat(d).asString()
                            .isEqualTo(read(summary, "$.nextDueDate").toString()).isEqualTo(dueDate))
                    .hasPathSatisfying("$.cards.upcomingEvents[0].kind", k -> assertThat(k).asString()
                            .isEqualTo("INVOICE_CLOSING"))
                    .hasPathSatisfying("$.cards.upcomingEvents[0].date", d -> assertThat(d).asString()
                            .isEqualTo(closingDate))
                    .hasPathSatisfying("$.cards.upcomingEvents[0].status", s -> assertThat(s).asString()
                            .isEqualTo("PARTIAL"))
                    .hasPathSatisfying("$.cards.upcomingEvents[0].amount.amount", a -> assertThat(a).asString()
                            .isEqualTo("100.00"))
                    .hasPathSatisfying("$.cards.upcomingEvents[0].card.last4", l -> assertThat(l).asString()
                            .isEqualTo("4821"))
                    .hasPathSatisfying("$.cards.asOf", d -> assertThat(d).asString().isEqualTo(today))
                    .hasPathSatisfying("$.cards.calculationVersion", v -> assertThat(v).asString()
                            .isEqualTo("invoice-v2"));
        }
    }

    private void assertOverview(String balance, String expense, int transfers) {
        assertThat(mvc.get().uri(WS + "/overview", workspace).with(ana())).hasStatus(HttpStatus.OK).bodyJson()
                .hasPathSatisfying("$.summary.totalBalance.amount", a -> assertThat(a).asString().isEqualTo(balance))
                .hasPathSatisfying("$.cashFlow.expense.amount", a -> assertThat(a).asString().isEqualTo(expense))
                .hasPathSatisfying("$.cashFlow.income.amount", a -> assertThat(a).asString().isEqualTo("0.00"))
                .hasPathSatisfying("$.cashFlow.transfers.count", n -> assertThat(n).asNumber().isEqualTo(transfers));
    }

    private MvcTestResult purchase(String idempotencyKey, String amount, int installments) {
        return post(CARD + "/purchases", """
                {"amount": {"amount": "%s", "currency": "BRL"}, "occurredOn": "%s", "description": "Compra secreta",
                 "installmentCount": %d}
                """.formatted(amount, today, installments), idempotencyKey, workspace, card);
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri, workspace, card).with(ana()).exchange();
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

    @SuppressWarnings("unchecked")
    private static <T> T read(MvcTestResult result, String path) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
        return (T) (value instanceof List<?> list && list.isEmpty() ? null : value);
    }

    private static RequestPostProcessor ana() {
        return jwt().jwt(t -> t.subject("sub-ana").claim("iss", ISSUER).claim("email", "ana@example.com")
                .claim("name", "Ana"));
    }
}
