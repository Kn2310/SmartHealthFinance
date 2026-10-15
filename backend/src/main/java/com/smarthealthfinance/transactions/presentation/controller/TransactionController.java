package com.smarthealthfinance.transactions.presentation.controller;

import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.transactions.application.usecase.AutoCategorizePending;
import com.smarthealthfinance.transactions.application.usecase.CancelTransaction;
import com.smarthealthfinance.transactions.application.usecase.CategorizeTransaction;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction;
import com.smarthealthfinance.transactions.application.usecase.GetTransaction;
import com.smarthealthfinance.transactions.application.usecase.ListTransactions;
import com.smarthealthfinance.transactions.application.usecase.MarkTransactionAsRefund;
import com.smarthealthfinance.transactions.application.usecase.PostTransaction;
import com.smarthealthfinance.transactions.application.usecase.ReverseTransaction;
import com.smarthealthfinance.transactions.application.usecase.SummarizeTransactions;
import com.smarthealthfinance.transactions.application.usecase.UpdateTransaction;
import com.smarthealthfinance.transactions.presentation.dto.request.CategorizeTransactionRequest;
import com.smarthealthfinance.transactions.presentation.dto.request.CreateTransactionRequest;
import com.smarthealthfinance.transactions.presentation.dto.request.MarkAsRefundRequest;
import com.smarthealthfinance.transactions.presentation.dto.request.UpdateTransactionRequest;
import com.smarthealthfinance.transactions.presentation.dto.response.CategorizePendingResponse;
import com.smarthealthfinance.transactions.presentation.dto.response.TransactionPageResponse;
import com.smarthealthfinance.transactions.presentation.dto.response.TransactionResponse;
import com.smarthealthfinance.transactions.presentation.dto.response.TransactionSummaryResponse;
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
    private final CategorizeTransaction categorizeTransaction;
    private final AutoCategorizePending autoCategorizePending;
    private final SummarizeTransactions summarizeTransactions;
    private final MarkTransactionAsRefund markTransactionAsRefund;

    public TransactionController(ListTransactions listTransactions, GetTransaction getTransaction,
                                 CreateTransaction createTransaction, UpdateTransaction updateTransaction,
                                 PostTransaction postTransaction, CancelTransaction cancelTransaction,
                                 ReverseTransaction reverseTransaction, CategorizeTransaction categorizeTransaction,
                                 AutoCategorizePending autoCategorizePending,
                                 SummarizeTransactions summarizeTransactions,
                                 MarkTransactionAsRefund markTransactionAsRefund) {
        this.listTransactions = listTransactions;
        this.getTransaction = getTransaction;
        this.createTransaction = createTransaction;
        this.updateTransaction = updateTransaction;
        this.postTransaction = postTransaction;
        this.cancelTransaction = cancelTransaction;
        this.reverseTransaction = reverseTransaction;
        this.categorizeTransaction = categorizeTransaction;
        this.autoCategorizePending = autoCategorizePending;
        this.summarizeTransactions = summarizeTransactions;
        this.markTransactionAsRefund = markTransactionAsRefund;
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
            @Parameter(description = "Código da categoria (ADR-0012)")
            @RequestParam(required = false) String categoryCode,
            @Parameter(description = "true = só transações sem categoria; exclusivo com categoryCode")
            @RequestParam(required = false) Boolean uncategorized,
            @RequestParam(required = false) Integer page,
            @Parameter(description = "1 a 100 (padrão 50)")
            @RequestParam(required = false) Integer pageSize) {
        return TransactionPageResponse.from(listTransactions.execute(workspaceId, new ListTransactions.Query(
                from, to, type, status, accountId, q, categoryCode, uncategorized, page, pageSize)));
    }

    @GetMapping("/summary")
    @Operation(summary = "Métricas do período filtrado (mesmos filtros da lista): receitas, despesas, reembolsos, "
            + "transferências e resultado, só de transações lançadas")
    @ApiResponse(responseCode = "200", description = "Resumo")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (filtro inválido)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public TransactionSummaryResponse summary(
            @PathVariable UUID workspaceId,
            @Parameter(description = "occurredOn mínimo, inclusivo (YYYY-MM-DD)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "occurredOn máximo, inclusivo (YYYY-MM-DD)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String type,
            @Parameter(description = "Conta de origem ou de destino; com conta, os valores são dessa conta")
            @RequestParam(required = false) UUID accountId,
            @Parameter(description = "Trecho da descrição")
            @RequestParam(required = false) String q,
            @Parameter(description = "Código da categoria (ADR-0012)")
            @RequestParam(required = false) String categoryCode,
            @Parameter(description = "true = só transações sem categoria; exclusivo com categoryCode")
            @RequestParam(required = false) Boolean uncategorized) {
        return TransactionSummaryResponse.from(summarizeTransactions.execute(workspaceId, new ListTransactions.Query(
                from, to, type, null, accountId, q, categoryCode, uncategorized, null, null)));
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

    @PutMapping("/{transactionId}/category")
    @Operation(summary = "Atribui ou remove (categoryCode null) a categoria; idempotente, em qualquer status. A escolha é sempre MANUAL; com applyToSimilar, vira regra do comerciante para as transações semelhantes")
    @ApiResponse(responseCode = "200", description = "Transação atualizada")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (categoryCode INVALID, INACTIVE, NOT_ALLOWED ou INCOMPATIBLE_WITH_TYPE; applyToSimilar REQUIRES_CATEGORY ou NO_MERCHANT)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou TRANSACTION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CONFLICT (edição concorrente)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public TransactionResponse categorize(@PathVariable UUID workspaceId, @PathVariable UUID transactionId,
                                          @Valid @RequestBody CategorizeTransactionRequest request) {
        return TransactionResponse.from(categorizeTransaction.execute(workspaceId, transactionId,
                new CategorizeTransaction.Command(request.categoryCode(), Boolean.TRUE.equals(request.applyToSimilar()))));
    }

    @PostMapping("/categorize-pending")
    @Operation(summary = "Identifica comerciantes e aplica as regras às transações ainda não tratadas; idempotente, nunca altera escolha do usuário")
    @ApiResponse(responseCode = "200", description = "Resumo da categorização")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CategorizePendingResponse categorizePending(@PathVariable UUID workspaceId) {
        return CategorizePendingResponse.from(autoCategorizePending.execute(workspaceId));
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

    @PostMapping("/{transactionId}/mark-as-refund")
    @Operation(summary = "Marca uma receita importada como reembolso: estorna a receita e cria um REFUND igual, opcionalmente vinculado à despesa original (idempotente; responde o reembolso)")
    @ApiResponse(responseCode = "201", description = "Reembolso criado e receita estornada")
    @ApiResponse(responseCode = "200", description = "Replay: a receita já tinha sido marcada com o mesmo vínculo")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (transactionId NOT_RECLASSIFIABLE; refundOfTransactionId NOT_FOUND, NOT_REFUNDABLE ou ALREADY_RECLASSIFIED; amount EXCEEDS_REFUNDABLE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou TRANSACTION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "TRANSACTION_STATUS_CONFLICT (receita não lançada) ou ACCOUNT_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransactionResponse> markAsRefund(@PathVariable UUID workspaceId,
                                                            @PathVariable UUID transactionId,
                                                            @RequestBody(required = false) MarkAsRefundRequest request) {
        MarkTransactionAsRefund.Result result = markTransactionAsRefund.execute(workspaceId, transactionId,
                new MarkTransactionAsRefund.Command(request == null ? null : request.refundOfTransactionId()));

        TransactionResponse body = TransactionResponse.from(result.refund());
        if (result.replayed()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/transactions/" + body.id()))
                .body(body);
    }
}
