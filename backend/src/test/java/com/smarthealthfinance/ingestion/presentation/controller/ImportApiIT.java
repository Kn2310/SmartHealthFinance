package com.smarthealthfinance.ingestion.presentation.controller;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Fluxo completo com PostgreSQL e RabbitMQ reais: upload → preview → confirmação (202) → outbox → relay →
 * Import Worker → transações (ADR-0009).
 */
@ExtendWith(OutputCaptureExtension.class)
class ImportApiIT extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String ACCOUNTS = "/api/v1/workspaces/{workspaceId}/accounts";
    private static final String IMPORTS = "/api/v1/workspaces/{workspaceId}/imports";
    private static final String IMPORT = IMPORTS + "/{importId}";
    private static final String TRANSACTIONS = "/api/v1/workspaces/{workspaceId}/transactions";

    private static final String SEPTEMBER = """
            data;descricao;valor;id
            01/09/2026;Salário Empresa Aurora;4.500,00;s1
            02/09/2026;Bistrô Lume;-86,40;s2
            03/09/2026;Valor ruim;abc;s3
            03/09/2026;Café;-5,00;s4
            """;

    private static final String OFX = """
            OFXHEADER:100
            DATA:OFXSGML
            CHARSET:1252

            <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL
            <BANKACCTFROM><BANKID>0000<ACCTID>00000-0<ACCTTYPE>CHECKING</BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260904<TRNAMT>-210.35<FITID>o1<MEMO>Mercado Sol</STMTTRN>
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20260905<TRNAMT>150.00<FITID>o2<MEMO>Pix recebido</STMTTRN>
            </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
            """;

    @Autowired
    JdbcTemplate jdbc;

    private String anasWorkspace;
    private String bobsWorkspace;
    private String aurora;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        anasWorkspace = read(mvc.post().uri(ME).with(ana()).exchange(), "$.workspaceId").toString();
        bobsWorkspace = read(mvc.post().uri(ME).with(bob()).exchange(), "$.workspaceId").toString();
        aurora = read(mvc.post().uri(ACCOUNTS, anasWorkspace).with(ana()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Banco Aurora", "type": "CHECKING"}
                        """).exchange(), "$.id").toString();
    }

    @Test
    void uploadsPreviewsConfirmsAndProcessesAsynchronously() throws Exception {
        MvcTestResult upload = upload("k-1", "extrato.csv", SEPTEMBER);

        assertThat(upload).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.status", v -> assertThat(v).asString().isEqualTo("PREVIEW"))
                .hasPathSatisfying("$.format", v -> assertThat(v).asString().isEqualTo("CSV"))
                .hasPathSatisfying("$.lines.total", v -> assertThat(v).isEqualTo(4))
                .hasPathSatisfying("$.lines.valid", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.lines.invalid", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.period.from", v -> assertThat(v).asString().isEqualTo("2026-09-01"))
                .hasPathSatisfying("$.period.to", v -> assertThat(v).asString().isEqualTo("2026-09-03"));
        String importId = read(upload, "$.id").toString();
        assertThat(upload.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/workspaces/" + anasWorkspace + "/imports/" + importId);
        assertThat(countTransactions()).isZero();

        MvcTestResult records = mvc.get().uri(IMPORT + "/records", anasWorkspace, importId).with(ana()).exchange();
        assertThat(records).hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.items[1].amount.amount", v -> assertThat(v).asString().isEqualTo("86.40"))
                .hasPathSatisfying("$.items[1].direction", v -> assertThat(v).asString().isEqualTo("OUTFLOW"))
                .hasPathSatisfying("$.items[2].status", v -> assertThat(v).asString().isEqualTo("INVALID"))
                .hasPathSatisfying("$.items[2].issue.code", v -> assertThat(v).asString().isEqualTo("INVALID_AMOUNT"));

        MvcTestResult confirm = mvc.post().uri(IMPORT + "/confirm", anasWorkspace, importId).with(ana()).exchange();
        assertThat(confirm).hasStatus(HttpStatus.ACCEPTED);

        awaitStatus(importId, "COMPLETED");
        MvcTestResult done = get(importId);
        assertThat(read(done, "$.lines.imported")).isEqualTo(3);
        assertThat(jdbc.queryForList("select type from transactions where source = 'IMPORT' and status = 'POSTED'",
                String.class)).containsExactlyInAnyOrder("INCOME", "EXPENSE", "EXPENSE");
        assertThat(jdbc.queryForObject("select amount::text from transactions where description = 'Bistrô Lume'",
                String.class)).isEqualTo("86.4000");

        MvcTestResult listed = mvc.get().uri(TRANSACTIONS, anasWorkspace).with(ana()).exchange();
        assertThat(listed).bodyJson().hasPathSatisfying("$.items[0].source",
                v -> assertThat(v).asString().isEqualTo("IMPORT"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from outbox_events where event_type = 'import.completed' and published_at is not null",
                Integer.class)).isEqualTo(1));
    }

    @Test
    void reimportingAnOverlappingOfxNeverDuplicates() throws Exception {
        String first = read(upload("k-1", "setembro.ofx", OFX), "$.id").toString();
        confirm(first);
        awaitStatus(first, "COMPLETED");

        MvcTestResult again = upload("k-2", "setembro.ofx", OFX);

        assertThat(again).hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.format", v -> assertThat(v).asString().isEqualTo("OFX"))
                .hasPathSatisfying("$.lines.valid", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.lines.duplicate", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.sameFileImportedBefore", v -> assertThat(v).isEqualTo(true));
        String second = read(again, "$.id").toString();
        confirm(second);
        awaitStatus(second, "COMPLETED");
        assertThat(countTransactions()).isEqualTo(2);
    }

    /** Dois previews do mesmo arquivo confirmados juntos: a reserva da chave no banco decide quem importa. */
    @Test
    void concurrentImportsOfTheSameFileImportOnce() throws Exception {
        String first = read(upload("k-1", "a.csv", SEPTEMBER), "$.id").toString();
        String second = read(upload("k-2", "b.csv", SEPTEMBER), "$.id").toString();

        confirm(first);
        confirm(second);
        awaitStatus(first, "COMPLETED");
        awaitStatus(second, "COMPLETED");

        assertThat(countTransactions()).isEqualTo(3);
        assertThat((Integer) read(get(first), "$.lines.imported") + (Integer) read(get(second), "$.lines.imported"))
                .isEqualTo(3);
    }

    @Test
    void concurrentConfirmationsEmitASingleEvent() throws Exception {
        String importId = read(upload("k-1", "extrato.csv", SEPTEMBER), "$.id").toString();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = java.util.stream.IntStream.range(0, 4).<Callable<Integer>>mapToObj(i -> () ->
                    mvc.post().uri(IMPORT + "/confirm", anasWorkspace, importId).with(ana()).exchange()
                            .getResponse().getStatus()).toList();
            for (Future<Integer> status : pool.invokeAll(calls)) {
                assertThat(status.get()).isEqualTo(202);
            }
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'import.confirmed'",
                Integer.class)).isEqualTo(1);
        awaitStatus(importId, "COMPLETED");
        assertThat(countTransactions()).isEqualTo(3);
    }

    @Test
    void idempotencyKeyIsRequiredAndBoundToTheFile() throws Exception {
        MvcTestResult first = upload("k-1", "extrato.csv", SEPTEMBER);
        MvcTestResult replay = upload("k-1", "extrato.csv", SEPTEMBER);
        MvcTestResult reused = upload("k-1", "extrato.csv", SEPTEMBER.replace("-5,00", "-6,00"));
        MvcTestResult missing = mvc.post().uri(IMPORTS, anasWorkspace).multipart()
                .file(new MockMultipartFile("file", "extrato.csv", "text/csv", bytes(SEPTEMBER)))
                .param("accountId", aurora).with(ana()).exchange();

        assertThat(replay).hasStatusOk();
        assertThat(read(replay, "$.id")).isEqualTo(read(first, "$.id"));
        assertThat(reused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(missing).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("select count(*) from import_batches", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectedFilesReturnAStableReasonAndCreateNothing() throws Exception {
        MvcTestResult wrongType = upload("k-1", "extrato.xlsx", "PK\u0003\u0004");
        MvcTestResult missingColumn = upload("k-2", "extrato.csv", "data;descricao\n01/09/2026;X\n");

        assertThat(wrongType).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo("IMPORT_FILE_REJECTED"))
                .hasPathSatisfying("$.details[0].code",
                        v -> assertThat(v).asString().isEqualTo("UNSUPPORTED_FILE_TYPE"));
        assertThat(missingColumn).bodyJson().hasPathSatisfying("$.details[0].code",
                v -> assertThat(v).asString().isEqualTo("MISSING_COLUMN"));
        assertThat(jdbc.queryForObject("select count(*) from import_batches", Integer.class)).isZero();
    }

    @Test
    void cancelDropsTheRecordsAndConfirmIsThenAConflict() throws Exception {
        String importId = read(upload("k-1", "extrato.csv", SEPTEMBER), "$.id").toString();

        MvcTestResult cancel = mvc.post().uri(IMPORT + "/cancel", anasWorkspace, importId).with(ana()).exchange();
        MvcTestResult confirm = mvc.post().uri(IMPORT + "/confirm", anasWorkspace, importId).with(ana()).exchange();

        assertThat(cancel).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", v -> assertThat(v).asString().isEqualTo("CANCELLED"));
        assertThat(confirm).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo("IMPORT_STATUS_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from import_records", Integer.class)).isZero();
    }

    @Test
    void anotherWorkspaceSeesNothing() throws Exception {
        String importId = read(upload("k-1", "extrato.csv", SEPTEMBER), "$.id").toString();

        assertThat(mvc.get().uri(IMPORT, anasWorkspace, importId).with(bob()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo("WORKSPACE_NOT_FOUND"));
        assertThat(mvc.get().uri(IMPORT, bobsWorkspace, importId).with(bob()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo("IMPORT_NOT_FOUND"));
        assertThat(mvc.post().uri(IMPORT + "/confirm", bobsWorkspace, importId).with(bob()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri(IMPORTS, bobsWorkspace).multipart()
                .file(new MockMultipartFile("file", "extrato.csv", "text/csv", bytes(SEPTEMBER)))
                .param("accountId", aurora).header("Idempotency-Key", "k-1").with(bob()).exchange())
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThat(read(mvc.get().uri(IMPORTS, bobsWorkspace).with(bob()).exchange(), "$.totalItems")).isEqualTo(0);
    }

    /** Valores, descrições e nome do arquivo são dados financeiros/pessoais: os logs carregam só IDs e contagens. */
    @Test
    void logsNeverCarryFileContent(CapturedOutput output) throws Exception {
        String importId = read(upload("k-1", "extrato-ana-silva.csv", SEPTEMBER), "$.id").toString();
        confirm(importId);
        awaitStatus(importId, "COMPLETED");

        assertThat(output.getAll())
                .contains("Import previewed importId=" + importId)
                .contains("Import completed importId=" + importId)
                .doesNotContain("Bistrô Lume")
                .doesNotContain("86,40")
                .doesNotContain("86.40")
                .doesNotContain("4.500,00")
                .doesNotContain("extrato-ana-silva");
    }

    private MvcTestResult upload(String key, String fileName, String content) {
        return mvc.post().uri(IMPORTS, anasWorkspace).multipart()
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes(content)))
                .param("accountId", aurora)
                .header("Idempotency-Key", key)
                .with(ana())
                .exchange();
    }

    private void confirm(String importId) {
        assertThat(mvc.post().uri(IMPORT + "/confirm", anasWorkspace, importId).with(ana()).exchange())
                .hasStatus(HttpStatus.ACCEPTED);
    }

    private MvcTestResult get(String importId) {
        return mvc.get().uri(IMPORT, anasWorkspace, importId).with(ana()).exchange();
    }

    private void awaitStatus(String importId, String status) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(read(get(importId), "$.status")).isEqualTo(status));
    }

    private Integer countTransactions() {
        return jdbc.queryForObject("select count(*) from transactions where workspace_id = ?::uuid", Integer.class,
                anasWorkspace);
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
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

    private static Object read(MvcTestResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
