package com.smarthealthfinance.forecast.presentation.controller;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.application.service.ForecastInputLoader;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * API de Forecast e recorrências (ADR-0032) sobre um snapshot real gravado pelo teste: envelope e estados, valores com
 * partes e faixa, cartão alheio, erros, ações (idempotência, estado do usuário depois do recálculo do job), isolamento e
 * logs sem dado financeiro.
 * <p>
 * Histórico da Ana relativo a hoje, nos 4 meses fechados anteriores: salário de 5.000 no dia 5 (EMPRESA), aluguel de
 * 1.000 no dia 15 (ALUGUEL) e duas compras de mercado por mês; saldo de 10.000. Gera uma série de receita e uma de
 * despesa.
 */
@ExtendWith(OutputCaptureExtension.class)
class ForecastApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String WS = "/api/v1/workspaces/{workspaceId}";
    private static final String FORECAST = WS + "/forecast";
    private static final String EXPENSES = WS + "/recurring-expenses";
    private static final String INCOMES = WS + "/recurring-incomes";
    private static final Currency BRL = Currency.getInstance("BRL");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ForecastSnapshotStore store;

    @Autowired
    RecurringSeriesRepository seriesRepository;

    @Autowired
    ForecastInputLoader loader;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MeterRegistry meters;

    private TransactionTemplate tx;
    private LocalDate today;
    private String anas;
    private String bobs;
    private String rentId;
    private String salaryId;
    private int keys;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        tx = new TransactionTemplate(transactionManager);
        today = loader.today();
        anas = str(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId");
        bobs = str(mvc.post().uri(ME).with(bob()).exchange(), "$.workspaceId");
        seedAnas();
        rentId = str(mvc.get().uri(EXPENSES, anas).with(ana()).exchange(), "$.items[0].id");
        salaryId = str(mvc.get().uri(INCOMES, anas).with(ana()).exchange(), "$.items[0].id");
    }

    // --- leituras ---

    @Test
    void monthIsServedFromTheSnapshotWithEnvelopePartsAndRange() throws Exception {
        MvcTestResult month = get(FORECAST + "/month");

        assertThat(month).hasStatus(HttpStatus.OK);
        assertThat(read(month, "$.state")).isEqualTo("READY");
        assertThat(read(month, "$.algorithmVersion")).isEqualTo("forecast-v1");
        assertThat(read(month, "$.asOf")).isEqualTo(today.toString());
        assertThat(read(month, "$.freshness.state")).isEqualTo("UP_TO_DATE");
        assertThat(read(month, "$.freshness.asOf")).isEqualTo(today.toString());
        assertThat(read(month, "$.freshness.computedAt")).isNotNull();
        assertThat(read(month, "$.month.month")).isEqualTo(YearMonth.from(today).toString());
        assertThat(((Number) read(month, "$.month.baseMonths")).intValue()).isPositive();
        for (String block : List.of("expense", "income", "net")) {
            assertConsistentValue(month, "$.month." + block);
        }
        // dinheiro como string; o salário vem das partes: o previsto é estimativa (nada realizado ainda neste mês)
        assertThat(read(month, "$.month.income.value.currency")).isEqualTo("BRL");
        assertThat(read(month, "$.month.income.value.amount")).isInstanceOf(String.class);
        assertThat(read(month, "$.month.expense.basis")).isEqualTo("ESTIMATED");
        assertThat(JsonPath.<List<?>>read(body(month), "$.month.expense.evidence")).isNotEmpty();
        assertThat(JsonPath.<String>read(body(month), "$.month.expense.evidence[0].kind")).isNotBlank();
        // nunca a chave do comerciante
        assertThat(body(month)).doesNotContain("merchant:");
    }

    @Test
    void nextIncomeUpcomingAndCashFlowAreServedWithTheSameEnvelope() throws Exception {
        MvcTestResult income = get(FORECAST + "/next-income");
        assertThat(income).hasStatus(HttpStatus.OK);
        assertThat(read(income, "$.state")).isEqualTo("READY");
        assertThat(read(income, "$.nextIncome.seriesId")).isEqualTo(salaryId);
        assertThat(read(income, "$.nextIncome.label")).isEqualTo("EMPRESA");
        assertThat(read(income, "$.nextIncome.channel.kind")).isEqualTo("ACCOUNT");
        assertConsistentValue(income, "$.nextIncome.amount");
        assertThat(read(income, "$.nextIncome.amount.basis")).isEqualTo("ESTIMATED");

        MvcTestResult upcoming = get(FORECAST + "/upcoming");
        assertThat(upcoming).hasStatus(HttpStatus.OK);
        assertThat(read(upcoming, "$.freshness.state")).isEqualTo("UP_TO_DATE");
        List<String> dates = JsonPath.read(body(upcoming), "$.events[*].date");
        assertThat(dates).isSorted();
        List<String> kinds = JsonPath.read(body(upcoming), "$.events[*].kind");
        assertThat(kinds).containsAnyOf("RECURRING_INCOME", "RECURRING_EXPENSE")
                .allMatch(k -> k.startsWith("RECURRING_") || k.startsWith("INVOICE_"));
        List<String> seriesIds = JsonPath.read(body(upcoming), "$.events[*].seriesId");
        assertThat(seriesIds).isNotEmpty().allMatch(id -> id.equals(rentId) || id.equals(salaryId));

        MvcTestResult cashFlow = get(FORECAST + "/cash-flow");
        assertThat(cashFlow).hasStatus(HttpStatus.OK);
        assertThat(read(cashFlow, "$.state")).isEqualTo("READY");
        assertThat(JsonPath.<List<?>>read(body(cashFlow), "$.cashFlow.months")).isNotEmpty();
        assertConsistentValue(cashFlow, "$.cashFlow.months[0].closing");
        assertThat(read(cashFlow, "$.cashFlow.troughNext30.lowestLowBound.date")).isNotNull();
        assertThat(new BigDecimal(str(cashFlow, "$.cashFlow.troughNext30.lowestLowBound.low.amount")))
                .isLessThanOrEqualTo(new BigDecimal(str(cashFlow, "$.cashFlow.troughNext30.lowestCentral.central.amount")));
    }

    @Test
    void invoicesFilterByCardAndAnUnknownOrForeignCardIsNotFound() throws Exception {
        String card = createCard(ana(), anas);
        String bobsCard = createCard(bob(), bobs);

        MvcTestResult all = get(FORECAST + "/invoices");
        assertThat(all).hasStatus(HttpStatus.OK);
        assertThat(read(all, "$.invoices")).isEqualTo(List.of());
        assertThat(get(FORECAST + "/invoices?cardId=" + card)).hasStatus(HttpStatus.OK);

        for (String foreign : List.of(bobsCard, UUID.randomUUID().toString())) {
            assertThat(get(FORECAST + "/invoices?cardId=" + foreign)).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("CARD_NOT_FOUND"));
        }
        assertThat(get(FORECAST + "/invoices?cardId=abc")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("VALIDATION_FAILED"))
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).isEqualTo("cardId"));
    }

    @Test
    void authorizationComesBeforeValidationAndANonMemberGetsWorkspaceNotFound() throws Exception {
        for (String path : List.of("/month", "/next-income", "/upcoming", "/cash-flow", "/invoices",
                "/invoices?cardId=abc")) {
            assertThat(mvc.get().uri(FORECAST + path, anas).with(bob()).exchange()).as(path)
                    .hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("WORKSPACE_NOT_FOUND"));
        }
    }

    @Test
    void stateIsNoDataThenInsufficientHistoryThenNotBuilt() throws Exception {
        // Workspace do Bob: sem transações
        MvcTestResult none = mvc.get().uri(FORECAST + "/month", bobs).with(bob()).exchange();
        assertThat(none).hasStatus(HttpStatus.OK);
        assertThat(read(none, "$.state")).isEqualTo("NO_DATA");
        assertThat(read(none, "$.freshness.state")).isEqualTo("UP_TO_DATE");
        assertThat(JsonPath.<Object>read(body(none), "$.month")).isNull();

        // uma só transação hoje: o job grava um snapshot só com fatos
        String account = createAccount(bob(), bobs);
        createExpense(bob(), bobs, account, today.toString(), "86.40");
        IdentityTables.awaitQuiescence(jdbc);
        MvcTestResult thin = mvc.get().uri(FORECAST + "/month", bobs).with(bob()).exchange();
        assertThat(read(thin, "$.state")).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(read(thin, "$.month.expense.basis")).isEqualTo("FACT");
        assertThat(new BigDecimal(str(thin, "$.month.expense.value.amount"))).isEqualByComparingTo("86.40");
        MvcTestResult noIncome = mvc.get().uri(FORECAST + "/next-income", bobs).with(bob()).exchange();
        assertThat(read(noIncome, "$.state")).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(JsonPath.<Object>read(body(noIncome), "$.nextIncome")).isNull();
        MvcTestResult noFlow = mvc.get().uri(FORECAST + "/cash-flow", bobs).with(bob()).exchange();
        assertThat(JsonPath.<Object>read(body(noFlow), "$.cashFlow")).isNull();

        // sem snapshot, com transações: NOT_BUILT e os blocos nulos
        jdbc.update("delete from forecast_snapshots where workspace_id = ?::uuid", bobs);
        MvcTestResult notBuilt = mvc.get().uri(FORECAST + "/month", bobs).with(bob()).exchange();
        assertThat(notBuilt).hasStatus(HttpStatus.OK);
        assertThat(read(notBuilt, "$.state")).isEqualTo("NOT_BUILT");
        assertThat(read(notBuilt, "$.freshness.state")).isEqualTo("NOT_BUILT");
        assertThat(JsonPath.<Object>read(body(notBuilt), "$.month")).isNull();
        assertThat(JsonPath.<Object>read(body(notBuilt), "$.asOf")).isNull();
        MvcTestResult upcoming = mvc.get().uri(FORECAST + "/upcoming", bobs).with(bob()).exchange();
        assertThat(read(upcoming, "$.events")).isEqualTo(List.of());
    }

    @Test
    void aSnapshotFromAPreviousDayIsServedAsIsAndMarksTheWorkspace() throws Exception {
        jdbc.update("update forecast_snapshots set as_of = as_of - 1 where workspace_id = ?::uuid", anas);
        jdbc.update("update forecast_workspaces set dirty_since = null, due_at = null where workspace_id = ?::uuid",
                anas);

        MvcTestResult month = get(FORECAST + "/month");

        assertThat(month).hasStatus(HttpStatus.OK);
        assertThat(read(month, "$.freshness.state")).isEqualTo("RECALCULATING");
        assertThat(read(month, "$.freshness.asOf")).isEqualTo(today.minusDays(1).toString());
        // a leitura marcou o Workspace; o job recalcula e a próxima leitura já tem a data de hoje
        IdentityTables.awaitQuiescence(jdbc);
        assertThat(read(get(FORECAST + "/status"), "$.asOf")).isEqualTo(today.toString());
    }

    @Test
    void servedLogsCarryNoFinancialData(CapturedOutput output) throws Exception {
        get(FORECAST + "/month");
        get(FORECAST + "/next-income");
        get(FORECAST + "/upcoming");

        assertThat(output.getOut()).contains("Forecast served workspaceId=" + anas + " view=month state=READY")
                .contains("view=next-income").contains("view=upcoming");
        assertThat(output.getAll()).doesNotContain("EMPRESA").doesNotContain("ALUGUEL").doesNotContain("5000")
                .doesNotContain("10000").doesNotContain("1000.00");
        assertThat(meters.find("shf.forecast.query").tag("view", "month").tag("state", "READY").timer())
                .isNotNull();
    }

    // --- recorrências ---

    @Test
    void listAndDetailExposeEffectiveAndDetectedValuesOnTheRightDirection() throws Exception {
        MvcTestResult expenses = get(EXPENSES);
        assertThat(expenses).hasStatus(HttpStatus.OK);
        assertThat(JsonPath.<List<?>>read(body(expenses), "$.items")).hasSize(1);
        assertThat(read(expenses, "$.items[0].id")).isEqualTo(rentId);
        assertThat(read(expenses, "$.items[0].direction")).isEqualTo("EXPENSE");
        assertThat(read(expenses, "$.items[0].label")).isEqualTo("ALUGUEL");
        assertThat(read(expenses, "$.items[0].periodicity")).isEqualTo("MONTHLY");
        assertThat(read(expenses, "$.items[0].expectedDay")).isEqualTo(15);
        assertThat(new BigDecimal(str(expenses, "$.items[0].expectedAmount.amount"))).isEqualByComparingTo("1000.00");
        assertThat(read(expenses, "$.items[0].detected.expectedDay")).isEqualTo(15);
        assertThat(read(expenses, "$.items[0].userAdjusted")).isEqualTo(false);
        assertThat(read(expenses, "$.items[0].status")).isEqualTo("DETECTED");
        assertThat(read(expenses, "$.items[0].origin")).isEqualTo("DETECTED");
        assertThat(read(expenses, "$.items[0].eligibility")).isEqualTo("ELIGIBLE");
        assertThat(read(expenses, "$.items[0].realizedThisMonth")).isEqualTo(false);
        assertThat(read(expenses, "$.items[0].nextExpectedDate")).isNotNull();
        assertThat(JsonPath.<List<?>>read(body(expenses), "$.items[0].occurrences")).isNotEmpty();
        assertThat(read(expenses, "$.items[0].algorithmVersion")).isEqualTo("forecast-v1");
        assertThat(body(expenses)).doesNotContain("merchant:");

        MvcTestResult incomes = get(INCOMES);
        assertThat(read(incomes, "$.items[0].id")).isEqualTo(salaryId);
        assertThat(read(incomes, "$.items[0].direction")).isEqualTo("INCOME");

        assertThat(read(get(EXPENSES + "/" + rentId), "$.id")).isEqualTo(rentId);
        assertThat(read(get(EXPENSES + "?status=CONFIRMED"), "$.items")).isEqualTo(List.of());
        assertThat(get(EXPENSES + "?status=nope")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .hasPathSatisfying("$.details[0].field", f -> assertThat(f).isEqualTo("status"));
    }

    @Test
    void aSeriesOfAnotherDirectionWorkspaceOrIdIsNotFound() throws Exception {
        for (String path : List.of(INCOMES + "/" + rentId, EXPENSES + "/" + salaryId,
                EXPENSES + "/" + UUID.randomUUID())) {
            assertThat(get(path)).as(path).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("RECURRING_SERIES_NOT_FOUND"));
        }
        assertThat(post(INCOMES + "/" + rentId + "/confirm")).hasStatus(HttpStatus.NOT_FOUND);
        // o id da Ana dentro do Workspace do Bob
        assertThat(mvc.get().uri(EXPENSES + "/" + rentId, bobs).with(bob()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("RECURRING_SERIES_NOT_FOUND"));
        assertThat(read(mvc.get().uri(EXPENSES, bobs).with(bob()).exchange(), "$.items")).isEqualTo(List.of());
    }

    @Test
    void confirmIgnoreAndConfirmAgainAreIdempotentAndAnswerRecalculating() throws Exception {
        double before = actionCount("confirm");

        MvcTestResult confirmed = post(EXPENSES + "/" + rentId + "/confirm");
        assertThat(confirmed).hasStatus(HttpStatus.OK);
        assertThat(read(confirmed, "$.forecastState")).isEqualTo("RECALCULATING");
        assertThat(read(confirmed, "$.series.id")).isEqualTo(rentId);
        assertThat(read(confirmed, "$.series.status")).isEqualTo("CONFIRMED");
        assertThat(read(post(EXPENSES + "/" + rentId + "/confirm"), "$.series.status")).isEqualTo("CONFIRMED");
        assertThat(actionCount("confirm")).isEqualTo(before + 2);

        assertThat(read(post(EXPENSES + "/" + rentId + "/ignore"), "$.series.status")).isEqualTo("IGNORED");
        assertThat(read(post(EXPENSES + "/" + rentId + "/ignore"), "$.series.status")).isEqualTo("IGNORED");
        assertThat(read(get(EXPENSES + "?status=IGNORED"), "$.items[0].id")).isEqualTo(rentId);
        // confirmar desfaz o ignorar
        assertThat(read(post(EXPENSES + "/" + rentId + "/confirm"), "$.series.status")).isEqualTo("CONFIRMED");
        assertThat(actionCount("ignore")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void adjustAndClearChangeOnlyTheEffectiveValuesAndKeepStatusAndEligibility() throws Exception {
        MvcTestResult adjusted = put(EXPENSES + "/" + rentId + "/adjustment", """
                {"expectedAmount": {"amount": "1234.50", "currency": "BRL"}, "expectedDay": 20}
                """);

        assertThat(adjusted).hasStatus(HttpStatus.OK);
        assertThat(read(adjusted, "$.forecastState")).isEqualTo("RECALCULATING");
        assertThat(read(adjusted, "$.series.userAdjusted")).isEqualTo(true);
        assertThat(read(adjusted, "$.series.expectedDay")).isEqualTo(20);
        assertThat(new BigDecimal(str(adjusted, "$.series.expectedAmount.amount"))).isEqualByComparingTo("1234.50");
        assertThat(new BigDecimal(str(adjusted, "$.series.amountRange.low.amount"))).isEqualByComparingTo("1234.50");
        assertThat(new BigDecimal(str(adjusted, "$.series.amountRange.high.amount"))).isEqualByComparingTo("1234.50");
        // os detectados continuam à vista; estado e elegibilidade não mudam
        assertThat(read(adjusted, "$.series.detected.expectedDay")).isEqualTo(15);
        assertThat(new BigDecimal(str(adjusted, "$.series.detected.expectedAmount.amount")))
                .isEqualByComparingTo("1000.00");
        assertThat(read(adjusted, "$.series.status")).isEqualTo("DETECTED");
        assertThat(read(adjusted, "$.series.eligibility")).isEqualTo("ELIGIBLE");

        // só o dia: o valor ajustado anterior é mantido
        MvcTestResult dayOnly = put(EXPENSES + "/" + rentId + "/adjustment", "{\"expectedDay\": 31}");
        assertThat(read(dayOnly, "$.series.expectedDay")).isEqualTo(31);
        assertThat(new BigDecimal(str(dayOnly, "$.series.expectedAmount.amount"))).isEqualByComparingTo("1234.50");

        MvcTestResult cleared = delete(EXPENSES + "/" + rentId + "/adjustment");
        assertThat(cleared).hasStatus(HttpStatus.OK);
        assertThat(read(cleared, "$.series.userAdjusted")).isEqualTo(false);
        assertThat(read(cleared, "$.series.expectedDay")).isEqualTo(15);
        assertThat(new BigDecimal(str(cleared, "$.series.expectedAmount.amount"))).isEqualByComparingTo("1000.00");
        assertThat(read(delete(EXPENSES + "/" + rentId + "/adjustment"), "$.series.userAdjusted")).isEqualTo(false);
    }

    @Test
    void invalidAdjustmentsAreRejectedWithDetailsAndChangeNothing() throws Exception {
        for (String[] bad : new String[][] {
                {"{}", "expectedAmount"},
                {"{\"expectedDay\": 0}", "expectedDay"},
                {"{\"expectedDay\": 32}", "expectedDay"},
                {"{\"expectedAmount\": {\"amount\": \"0\", \"currency\": \"BRL\"}}", "expectedAmount"},
                {"{\"expectedAmount\": {\"amount\": \"-5.00\", \"currency\": \"BRL\"}}", "expectedAmount"},
                {"{\"expectedAmount\": {\"amount\": \"10.005\", \"currency\": \"BRL\"}}", "expectedAmount"},
                {"{\"expectedAmount\": {\"amount\": \"abc\", \"currency\": \"BRL\"}}", "expectedAmount"},
                {"{\"expectedAmount\": {\"amount\": \"10.00\", \"currency\": \"USD\"}}", "expectedAmount"}}) {
            assertThat(put(EXPENSES + "/" + rentId + "/adjustment", bad[0])).as(bad[0])
                    .hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                    .hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("VALIDATION_FAILED"))
                    .hasPathSatisfying("$.details[0].field", f -> assertThat(f).isEqualTo(bad[1]));
        }
        assertThat(read(get(EXPENSES + "/" + rentId), "$.userAdjusted")).isEqualTo(false);
        // autorização antes da validação: um corpo inválido de um não membro continua 404 do Workspace
        assertThat(mvc.put().uri(EXPENSES + "/" + rentId + "/adjustment", anas).with(bob())
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().hasPathSatisfying("$.code", c -> assertThat(c).isEqualTo("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void theUsersStateSurvivesTheRecalculationTheActionTriggers() throws Exception {
        Instant computedBefore = computedAt();
        post(EXPENSES + "/" + rentId + "/confirm");
        put(EXPENSES + "/" + rentId + "/adjustment", "{\"expectedDay\": 9}");
        post(INCOMES + "/" + salaryId + "/ignore");

        // a ação marca o Workspace para já; o job (a cada 200 ms nos testes) recalcula com o histórico real do banco
        IdentityTables.awaitQuiescence(jdbc);
        assertThat(computedAt()).isAfter(computedBefore);

        MvcTestResult rent = get(EXPENSES + "/" + rentId);
        assertThat(read(rent, "$.status")).isEqualTo("CONFIRMED");
        assertThat(read(rent, "$.userAdjusted")).isEqualTo(true);
        assertThat(read(rent, "$.expectedDay")).isEqualTo(9);
        assertThat(read(rent, "$.detected.expectedDay")).isEqualTo(15);
        assertThat(read(get(INCOMES + "/" + salaryId), "$.status")).isEqualTo("IGNORED");
        // o id também sobrevive
        assertThat(read(get(EXPENSES), "$.items[0].id")).isEqualTo(rentId);
    }

    @Test
    void everyActionMarksTheWorkspaceSoTheJobRecalculatesIt() {
        Instant before = computedAt();

        assertThat(post(EXPENSES + "/" + rentId + "/confirm")).hasStatus(HttpStatus.OK);
        IdentityTables.awaitQuiescence(jdbc);

        assertThat(computedAt()).isAfter(before);
    }

    // --- apoio ---

    private double actionCount(String action) {
        var counter = meters.find("shf.forecast.recurrence.action").tag("action", action).counter();
        return counter == null ? 0 : counter.count();
    }

    private Instant computedAt() {
        return jdbc.queryForObject("select computed_at from forecast_workspaces where workspace_id = ?::uuid",
                Timestamp.class, anas).toInstant();
    }

    /** value = factPart + estimatedPart, low <= value <= high, na mesma moeda. */
    private static void assertConsistentValue(MvcTestResult result, String path) throws Exception {
        BigDecimal value = new BigDecimal(str(result, path + ".value.amount"));
        BigDecimal fact = new BigDecimal(str(result, path + ".factPart.amount"));
        BigDecimal estimated = new BigDecimal(str(result, path + ".estimatedPart.amount"));
        BigDecimal low = new BigDecimal(str(result, path + ".range.low.amount"));
        BigDecimal high = new BigDecimal(str(result, path + ".range.high.amount"));
        assertThat(value).isEqualByComparingTo(fact.add(estimated));
        assertThat(low).isLessThanOrEqualTo(value);
        assertThat(value).isLessThanOrEqualTo(high);
        assertThat(read(result, path + ".basis").toString()).isIn("FACT", "ESTIMATED");
        assertThat(read(result, path + ".confidence").toString()).isIn("HIGH", "MEDIUM", "LOW");
        assertThat(JsonPath.<Object>read(body(result), path + ".limitations")).isInstanceOf(List.class);
    }

    private void seedAnas() {
        List<ForecastEntry> entries = new ArrayList<>();
        UUID account = UUID.randomUUID();
        int sequence = 0;
        for (int k = 4; k >= 1; k--) {
            YearMonth month = YearMonth.from(today).minusMonths(k);
            entries.add(entry(sequence++, month.atDay(5), CashFlowRole.INCOME, "EMPRESA", "5000.00", account));
            entries.add(entry(sequence++, month.atDay(15), CashFlowRole.EXPENSE, "ALUGUEL", "1000.00", account));
            entries.add(entry(sequence++, month.atDay(8), CashFlowRole.EXPENSE, "MERCADO", "150.55", account));
            entries.add(entry(sequence++, month.atDay(20), CashFlowRole.EXPENSE, "MERCADO", "149.45", account));
        }
        LocalDate first = YearMonth.from(today).minusMonths(4).atDay(5);
        ForecastResult result = ForecastCalculator.calculate(new ForecastInput(today, BRL, Optional.of(first), entries,
                List.of(), Money.of("10000.00", BRL), List.of(), List.of()));
        assertThat(result.series()).hasSize(2);
        Instant now = Instant.now();
        WorkspaceId id = new WorkspaceId(UUID.fromString(anas));
        tx.executeWithoutResult(s -> {
            seriesRepository.upsertDetected(id, result.series().stream().map(SeriesAssessment::series).toList(), now);
            store.replace(id, result, now);
        });
    }

    private static ForecastEntry entry(int sequence, LocalDate date, CashFlowRole role, String merchant,
                                       String amount, UUID account) {
        return new ForecastEntry(UUID.nameUUIDFromBytes(("tx-" + sequence).getBytes(StandardCharsets.UTF_8)), date,
                role, role == CashFlowRole.INCOME ? "salario" : "moradia", merchant, merchant,
                Money.of(amount, BRL), Channel.account(account), false);
    }

    private String createAccount(RequestPostProcessor who, String workspace) throws Exception {
        MvcTestResult result = mvc.post().uri(WS + "/accounts", workspace).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Conta\", \"type\": \"CHECKING\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return str(result, "$.id");
    }

    private void createExpense(RequestPostProcessor who, String workspace, String account, String day,
                               String amount) throws Exception {
        MvcTestResult result = mvc.post().uri(WS + "/transactions", workspace).with(who)
                .header("Idempotency-Key", "f-" + ++keys).contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "EXPENSE", "accountId": "%s", "amount": {"amount": "%s", "currency": "BRL"},
                         "occurredOn": "%s", "description": "Mercado"}
                        """.formatted(account, amount, day)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    private String createCard(RequestPostProcessor who, String workspace) throws Exception {
        MvcTestResult result = mvc.post().uri(WS + "/cards", workspace).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"nickname": "Cartao", "brand": "VISA", "last4": "1234",
                         "limit": {"amount": "1000.00", "currency": "BRL"}, "closingDay": 28, "dueDay": 5}
                        """).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return str(result, "$.id");
    }

    private MvcTestResult get(String path) {
        return mvc.get().uri(path, anas).with(ana()).exchange();
    }

    private MvcTestResult post(String path) {
        return mvc.post().uri(path, anas).with(ana()).exchange();
    }

    private MvcTestResult put(String path, String json) {
        return mvc.put().uri(path, anas).with(ana()).contentType(MediaType.APPLICATION_JSON).content(json)
                .exchange();
    }

    private MvcTestResult delete(String path) {
        return mvc.delete().uri(path, anas).with(ana()).exchange();
    }

    private static RequestPostProcessor ana() {
        return identity("sub-ana", "ana@example.com");
    }

    private static RequestPostProcessor bob() {
        return identity("sub-bob", "bob@example.com");
    }

    private static RequestPostProcessor identity(String subject, String email) {
        return jwt().jwt(token -> token.subject(subject).claim("iss", ISSUER).claim("email", email)
                .claim("name", "Nome"));
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String str(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }
}
