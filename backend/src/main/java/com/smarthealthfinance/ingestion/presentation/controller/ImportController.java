package com.smarthealthfinance.ingestion.presentation.controller;

import com.smarthealthfinance.ingestion.application.usecase.CancelImport;
import com.smarthealthfinance.ingestion.application.usecase.ConfirmImport;
import com.smarthealthfinance.ingestion.application.usecase.GetImport;
import com.smarthealthfinance.ingestion.application.usecase.ListImportRecords;
import com.smarthealthfinance.ingestion.application.usecase.ListImports;
import com.smarthealthfinance.ingestion.application.usecase.UploadStatement;
import com.smarthealthfinance.ingestion.presentation.dto.response.ImportPageResponses.ImportPageResponse;
import com.smarthealthfinance.ingestion.presentation.dto.response.ImportPageResponses.ImportRecordPageResponse;
import com.smarthealthfinance.ingestion.presentation.dto.response.ImportResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * Importação de extratos CSV/OFX (spec 05.6: multipart + processamento assíncrono + 202 + status; ADR-0009 §17).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/imports")
@Tag(name = "Imports")
public class ImportController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final UploadStatement uploadStatement;
    private final ConfirmImport confirmImport;
    private final CancelImport cancelImport;
    private final GetImport getImport;
    private final ListImports listImports;
    private final ListImportRecords listImportRecords;

    public ImportController(UploadStatement uploadStatement, ConfirmImport confirmImport, CancelImport cancelImport,
                            GetImport getImport, ListImports listImports, ListImportRecords listImportRecords) {
        this.uploadStatement = uploadStatement;
        this.confirmImport = confirmImport;
        this.cancelImport = cancelImport;
        this.getImport = getImport;
        this.listImports = listImports;
        this.listImportRecords = listImportRecords;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Envia um extrato (.csv ou .ofx, até 10 MB) e devolve o preview, sem criar transações")
    @ApiResponse(responseCode = "201", description = "Preview criado (status PREVIEW)")
    @ApiResponse(responseCode = "200", description = "Replay: a mesma chave e arquivo já foram enviados")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (inclui Idempotency-Key ausente)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "ACCOUNT_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "PAYLOAD_TOO_LARGE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422",
            description = "IMPORT_FILE_REJECTED (motivo em details[0].code) ou IDEMPOTENCY_KEY_REUSED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ImportResponse> upload(
            @PathVariable UUID workspaceId,
            @Parameter(description = "Chave única por envio (ex.: UUID gerado pelo cliente)", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Parameter(description = "Conta que recebe os lançamentos") @RequestParam UUID accountId,
            @RequestPart("file") MultipartFile file) throws IOException {
        UploadStatement.Result result = uploadStatement.execute(workspaceId, new UploadStatement.Command(
                idempotencyKey, accountId, file.getOriginalFilename(), file.getBytes()));

        ImportResponse body = ImportResponse.from(result.batch());
        if (result.replayed()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/imports/" + body.id()))
                .body(body);
    }

    @GetMapping
    @Operation(summary = "Histórico de importações do Workspace, mais recentes primeiro")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ImportPageResponse list(@PathVariable UUID workspaceId,
                                   @RequestParam(required = false) Integer page,
                                   @Parameter(description = "1 a 100 (padrão 20)")
                                   @RequestParam(required = false) Integer pageSize) {
        return ImportPageResponse.from(listImports.execute(workspaceId, page, pageSize));
    }

    @GetMapping("/{importId}")
    @Operation(summary = "Status e contagens de uma importação (polling do processamento)")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou IMPORT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ImportResponse get(@PathVariable UUID workspaceId, @PathVariable UUID importId) {
        return ImportResponse.from(getImport.execute(workspaceId, importId));
    }

    @GetMapping("/{importId}/records")
    @Operation(summary = "Linhas do arquivo (preview ou resultado), em ordem de arquivo")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou IMPORT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ImportRecordPageResponse records(
            @PathVariable UUID workspaceId, @PathVariable UUID importId,
            @Parameter(description = "VALID, INVALID, DUPLICATE ou IMPORTED")
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @Parameter(description = "1 a 100 (padrão 50)") @RequestParam(required = false) Integer pageSize) {
        return ImportRecordPageResponse.from(listImportRecords.execute(workspaceId, importId, status, page,
                pageSize));
    }

    @PostMapping("/{importId}/confirm")
    @Operation(summary = "Confirma o preview; as transações são criadas de forma assíncrona (idempotente)")
    @ApiResponse(responseCode = "202", description = "Confirmado; acompanhe pelo GET da importação")
    @ApiResponse(responseCode = "409", description = "IMPORT_STATUS_CONFLICT (cancelada/expirada) ou ACCOUNT_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ImportResponse> confirm(@PathVariable UUID workspaceId, @PathVariable UUID importId) {
        return ResponseEntity.accepted().body(ImportResponse.from(confirmImport.execute(workspaceId, importId)));
    }

    @PostMapping("/{importId}/cancel")
    @Operation(summary = "Descarta o preview (idempotente); depois da confirmação não há cancelamento")
    @ApiResponse(responseCode = "409", description = "IMPORT_STATUS_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ImportResponse cancel(@PathVariable UUID workspaceId, @PathVariable UUID importId) {
        return ImportResponse.from(cancelImport.execute(workspaceId, importId));
    }
}
