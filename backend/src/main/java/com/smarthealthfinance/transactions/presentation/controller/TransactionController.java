package com.smarthealthfinance.transactions.presentation.controller;

import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.transactions.application.usecase.CancelTransaction;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction;
import com.smarthealthfinance.transactions.application.usecase.GetTransaction;
import com.smarthealthfinance.transactions.application.usecase.ListTransactions;
import com.smarthealthfinance.transactions.application.usecase.PostTransaction;
import com.smarthealthfinance.transactions.application.usecase.ReverseTransaction;
import com.smarthealthfinance.transactions.application.usecase.UpdateTransaction;
import com.smarthealthfinance.transactions.presentation.dto.request.CreateTransactionRequest;
import com.smarthealthfinance.transactions.presentation.dto.request.UpdateTransactionRequest;
import com.smarthealthfinance.transactions.presentation.dto.response.TransactionPageResponse;
import com.smarthealthfinance.transactions.presentation.dto.response.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/transactions")
@Tag(name = "Transactions")
public class TransactionController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ListTransactions listTransactions;
    private final GetTransaction getTransaction;
    private final CreateTransaction createTransaction;
    private final UpdateTransaction updateTransaction;
    private final PostTransaction postTransaction;
    private final CancelTransaction cancelTransaction;
    private final ReverseTransaction reverseTransaction;

    public TransactionController(ListTransactions listTransactions, GetTransaction getTransaction,
                                 CreateTransaction createTransaction, UpdateTransaction updateTransaction,
                                 PostTransaction postTransaction, CancelTransaction cancelTransaction,
                                 ReverseTransaction reverseTransaction) {
        this.listTransactions = listTransactions;
        this.getTransaction = getTransaction;
        this.createTransaction = createTransaction;
        this.updateTransaction = updateTransaction;
        this.postTransaction = postTransaction;
        this.cancelTransaction = cancelTransaction;
        this.reverseTransaction = reverseTransaction;
    }

    @GetMapping
    @Operation(summary = "Lista as transações do Workspace, mais recentes primeiro (paginação por offset)")
    @ApiResponse(responseCode = "200", description = "Página de transações")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (filtro ou paginação inválidos)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public TransactionPageResponse list(
            @PathVariable UUID workspaceId,
            @Parameter(description = "occurredOn mínimo, inclusivo (YYYY-MM-DD)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "occurredOn máximo, inclusivo (YYYY-MM-DD)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @Parameter(description = "Conta de origem ou de destino")
            @RequestParam(required = false) UUID accountId,
            @Parameter(description = "Trecho da descrição")
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @Parameter(description = "1 a 100 (padrão 50)")
            @RequestParam(required = false) Integer pageSize) {
        return TransactionPageResponse.from(listTransactions.execute(workspaceId,
                new ListTransactions.Query(from, to, type, status, accountId, q, page, pageSize)));
    }

    @PostMapping
    @Operation(summary = "Registra uma transação manual (idempotente por Idempotency-Key)")
    @ApiResponse(responseCode = "201", description = "Transação criada")
    @ApiResponse(responseCode = "200", description = "Replay: a mesma chave e conteúdo já foram processados")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (inclui Idempotency-Key ausente)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "ACCOUNT_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransactionResponse> create(
            @PathVariable UUID workspaceId,
            @Parameter(description = "Chave única por intenção (ex.: UUID gerado pelo cliente)", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {
        CreateTransaction.Result result = createTransaction.execute(workspaceId, new CreateTransaction.Command(
                idempotencyKey, request.type(), request.accountId(), request.destinationAccountId(),
                request.adjustmentDirection(), request.amount().amount(), request.amount().currency(),
                request.occurredOn(), request.description(), request.status(), request.refundOfTransactionId()));

        TransactionResponse body = TransactionResponse.from(result.transaction());
        if (result.replayed()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/transactions/" + body.id()))
                .body(body);
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Consulta uma transação do Workspace")
    @ApiResponse(responseCode = "200", description = "Transação encontrada")
    @ApiResponse(responseCode = "404",
            description = "WORKSPACE_NOT_FOUND ou TRANSACTION_NOT_FOUND (inexistente ou de outro Workspace)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public TransactionResponse get(@PathVariable UUID workspaceId, @PathVariable UUID transactionId) {
        return TransactionResponse.from(getTransaction.execute(workspaceId, transactionId));
    }

    @PutMapping("/{transactionId}")
    @Operation(summary = "Edita a descrição (valor, tipo, contas e data são imutáveis)")
    @ApiResponse(responseCode = "200", description = "Transação atualizada")
    @ApiResponse(responseCode = "409", description = "CONFLICT (edição concorrente)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public TransactionResponse update(@PathVariable UUID workspaceId, @PathVariable UUID transactionId,
                                      @Valid @RequestBody UpdateTransactionRequest request) {
        return TransactionResponse.from(updateTransaction.execute(workspaceId, transactionId,
                new UpdateTransaction.Command(request.description())));
    }

    @PostMapping("/{transactionId}/post")
    @Operation(summary = "Efetiva uma transação pendente (PENDING → POSTED; idempotente)")
    @ApiResponse(responseCode = "409", description = "TRANSACTION_STATUS_CONFLICT ou ACCOUNT_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public TransactionResponse post(@PathVariable UUID workspaceId, @PathVariable UUID transactionId) {
        return TransactionResponse.from(postTransaction.execute(workspaceId, transactionId));
    }

    @PostMapping("/{transactionId}/cancel")
    @Operation(summary = "Cancela uma transação pendente (PENDING → CANCELLED; idempotente)")
    @ApiResponse(responseCode = "409", description = "TRANSACTION_STATUS_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public TransactionResponse cancel(@PathVariable UUID workspaceId, @PathVariable UUID transactionId) {
        return TransactionResponse.from(cancelTransaction.execute(workspaceId, transactionId));
    }

    @PostMapping("/{transactionId}/reverse")
    @Operation(summary = "Estorna uma transação lançada (POSTED → REVERSED; idempotente; o registro é mantido)")
    @ApiResponse(responseCode = "409", description = "TRANSACTION_STATUS_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public TransactionResponse reverse(@PathVariable UUID workspaceId, @PathVariable UUID transactionId) {
        return TransactionResponse.from(reverseTransaction.execute(workspaceId, transactionId));
    }
}
