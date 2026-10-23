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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@ExtendWith(OutputCaptureExtension.class)
class CardApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String CARDS = "/api/v1/workspaces/{workspaceId}/cards";
    private static final String CARD = CARDS + "/{cardId}";
    private static final String AURORA = """
            {"nickname": " Aurora Platinum ", "institutionName": "Banco Aurora", "brand": "MASTERCARD",
             "last4": "4821", "limit": {"amount": "12000.00", "currency": "BRL"}, "closingDay": 28, "dueDay": 5}
            """;

    @Autowired
    JdbcTemplate jdbc;

    private String anasWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
    }

    @Test
    void createsCard() throws Exception {
        MvcTestResult result = create(AURORA);

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.nickname", v -> assertThat(v).asString().isEqualTo("Aurora Platinum"))
                .hasPathSatisfying("$.brand", v -> assertThat(v).asString().isEqualTo("MASTERCARD"))
                .hasPathSatisfying("$.last4", v -> assertThat(v).asString().isEqualTo("4821"))
                .hasPathSatisfying("$.limit.amount", v -> assertThat(v).asString().isEqualTo("12000.00"))
                .hasPathSatisfying("$.limit.currency", v -> assertThat(v).asString().isEqualTo("BRL"))
                .hasPathSatisfying("$.status", v -> assertThat(v).asString().isEqualTo("ACTIVE"));
        String id = read(result, "$.id").toString();
        assertThat(UUID.fromString(id).version()).isEqualTo(7);
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/workspaces/" + anasWorkspace + "/cards/" + id);
        assertThat(read(result, "$.closingDay")).isEqualTo(28);
        assertThat(read(result, "$.defaultPaymentAccountId")).isNull();
        assertThat(countCards()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'card.created'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void refusesFullCardNumberCvvAndExpiryWithoutPersisting() {
        for (String extra : new String[] {"\"number\": \"4111111111111111\"", "\"cvv\": \"123\"",
                "\"expiry\": \"12/29\""}) {
            String body = AURORA.replace("\"closingDay\"", extra + ", \"closingDay\"");

            assertThat(create(body)).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("MALFORMED_REQUEST"));
        }
        assertThat(create(AURORA.replace("\"4821\"", "\"4111111111111111\""))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().isEqualTo("last4"))
                .hasPathSatisfying("$.details[0].code", c -> assertThat(c).asString().isEqualTo("INVALID"));
        assertThat(countCards()).isZero();
    }

    @Test
    void validatesCycleAndLimit() {
        assertValidation(AURORA.replace("\"dueDay\": 5", "\"dueDay\": 28"), "dueDay", "SAME_AS_CLOSING");
        assertValidation(AURORA.replace("\"closingDay\": 28", "\"closingDay\": 40"), "closingDay", "OUT_OF_RANGE");
        assertValidation(AURORA.replace("\"12000.00\"", "\"0\""), "limit", "NOT_POSITIVE");
        assertValidation(AURORA.replace("\"12000.00\"", "\"10.001\""), "limit", "TOO_MANY_DECIMALS");
        assertValidation(AURORA.replace("\"MASTERCARD\"", "\"DINERS\""), "brand", "INVALID");
        assertThat(countCards()).isZero();
    }

    @Test
    void newCardHasAnOpenEmptyCurrentInvoiceAndZeroUtilization() throws Exception {
        String card = read(create(AURORA), "$.id").toString();
        LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

        MvcTestResult current = mvc.get().uri(CARD + "/invoices/current", anasWorkspace, card).with(ana()).exchange();
        assertThat(current).hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.invoice.status", s -> assertThat(s).asString().isEqualTo("OPEN"))
                .hasPathSatisfying("$.invoice.amount.amount", a -> assertThat(a).asString().isEqualTo("0.00"))
                .hasPathSatisfying("$.invoice.datesSource", s -> assertThat(s).asString().isEqualTo("CALCULATED"))
                .hasPathSatisfying("$.calculationVersion", v -> assertThat(v).asString().isEqualTo("invoice-v2"))
                .hasPathSatisfying("$.asOf", v -> assertThat(v).asString().isEqualTo(today.toString()));
        assertThat(read(current, "$.invoice.partial")).isEqualTo(true);
        assertThat(read(current, "$.invoice.minimumPayment")).isNull();
        String dueMonth = read(current, "$.invoice.dueMonth").toString();

        assertThat(mvc.get().uri(CARD + "/invoices/{dueMonth}", anasWorkspace, card, dueMonth).with(ana()))
                .hasStatus(HttpStatus.OK);
        assertThat(mvc.get().uri(CARD + "/invoices/{dueMonth}/items", anasWorkspace, card, dueMonth).with(ana()))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.totalItems", t -> assertThat(t).asNumber().isEqualTo(0));
        // Fatura passada sem lançamentos não existe.
        String past = YearMonth.parse(dueMonth).minusMonths(3).toString();
        assertThat(mvc.get().uri(CARD + "/invoices/{dueMonth}", anasWorkspace, card, past).with(ana()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("INVOICE_NOT_FOUND"));
        assertThat(mvc.get().uri(CARD + "/invoices/{dueMonth}", anasWorkspace, card, "2026-13").with(ana()))
                .hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(mvc.get().uri(CARD + "/utilization", anasWorkspace, card).with(ana()))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.utilized.amount", a -> assertThat(a).asString().isEqualTo("0.00"))
                .hasPathSatisfying("$.available.amount", a -> assertThat(a).asString().isEqualTo("12000.00"))
                .hasPathSatisfying("$.utilizationPercent", p -> assertThat(p).asNumber().isEqualTo(0));
        assertThat(mvc.get().uri(CARD + "/invoices/future", anasWorkspace, card).with(ana()))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.totalCommitted.amount", a -> assertThat(a).asString().isEqualTo("0.00"));
        assertThat(mvc.get().uri(CARD + "/invoices", anasWorkspace, card).with(ana()))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.items.length()", n -> assertThat(n).asNumber().isEqualTo(1));
        assertThat(mvc.get().uri(CARDS + "/summary", anasWorkspace).with(ana()))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.totalLimit.amount", a -> assertThat(a).asString().isEqualTo("12000.00"))
                .hasPathSatisfying("$.openInvoices.amount", a -> assertThat(a).asString().isEqualTo("0.00"))
                .hasPathSatisfying("$.cards.length()", n -> assertThat(n).asNumber().isEqualTo(1));
    }

    @Test
    void updatesArchivesAndReactivates() throws Exception {
        String card = read(create(AURORA), "$.id").toString();
        String account = read(mvc.post().uri("/api/v1/workspaces/{workspaceId}/accounts", anasWorkspace).with(ana())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Conta Aurora", "type": "CHECKING"}
                        """).exchange(), "$.id").toString();

        assertThat(mvc.put().uri(CARD, anasWorkspace, card).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content(AURORA.replace("\"12000.00\"", "\"15000.00\"")
                        .replace("\"dueDay\": 5", "\"dueDay\": 5, \"defaultPaymentAccountId\": \"" + account + "\"")))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.limit.amount", a -> assertThat(a).asString().isEqualTo("15000.00"))
                .hasPathSatisfying("$.defaultPaymentAccountId", a -> assertThat(a).asString().isEqualTo(account));

        assertThat(mvc.post().uri(CARD + "/archive", anasWorkspace, card).with(ana())).hasStatus(HttpStatus.OK)
                .bodyJson().hasPathSatisfying("$.status", s -> assertThat(s).asString().isEqualTo("ARCHIVED"));
        assertThat(mvc.put().uri(CARD, anasWorkspace, card).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content(AURORA)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("CARD_ARCHIVED"));
        assertThat(mvc.get().uri(CARDS, anasWorkspace).with(ana())).bodyJson()
                .hasPathSatisfying("$.items.length()", n -> assertThat(n).asNumber().isEqualTo(0));
        assertThat(mvc.get().uri(CARDS, anasWorkspace).param("includeArchived", "true").with(ana())).bodyJson()
                .hasPathSatisfying("$.items.length()", n -> assertThat(n).asNumber().isEqualTo(1));

        assertThat(mvc.post().uri(CARD + "/reactivate", anasWorkspace, card).with(ana())).hasStatus(HttpStatus.OK)
                .bodyJson().hasPathSatisfying("$.status", s -> assertThat(s).asString().isEqualTo("ACTIVE"));
        assertThat(mvc.delete().uri(CARD, anasWorkspace, card).with(ana())).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void unknownCardIsNotFoundAndAnonymousIsUnauthorized() {
        assertThat(mvc.get().uri(CARD, anasWorkspace, UUID.randomUUID()).with(ana()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("CARD_NOT_FOUND"));
        assertThat(mvc.get().uri(CARDS, anasWorkspace)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logsOnlyIds(CapturedOutput output) throws Exception {
        String card = read(create(AURORA.replace("Aurora Platinum", "Cartão Secreto Pessoal")), "$.id").toString();
        mvc.put().uri(CARD, anasWorkspace, card).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content(AURORA.replace("Aurora Platinum", "Cartão Secreto Pessoal").replace("\"12000.00\"",
                        "\"98765.43\"")).exchange();

        assertThat(output).contains("Card created", "Card updated")
                .doesNotContain("Cartão Secreto Pessoal", "98765.43", "12000.00", "last4");
    }

    private MvcTestResult create(String json) {
        return mvc.post().uri(CARDS, anasWorkspace).with(ana()).contentType(MediaType.APPLICATION_JSON).content(json)
                .exchange();
    }

    private void assertValidation(String json, String field, String code) {
        assertThat(create(json)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).asString().isEqualTo(field))
                .hasPathSatisfying("$.details[0].code", c -> assertThat(c).asString().isEqualTo(code));
    }

    private Integer countCards() {
        return jdbc.queryForObject("select count(*) from credit_cards", Integer.class);
    }

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private static RequestPostProcessor ana() {
        return jwt().jwt(t -> t.subject("sub-ana").claim("iss", ISSUER).claim("email", "ana@example.com")
                .claim("name", "Ana"));
    }
}
