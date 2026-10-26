package com.smarthealthfinance.cards.presentation.controller;

import com.smarthealthfinance.cards.application.dto.CardPurchaseView;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.usecase.ArchiveCard;
import com.smarthealthfinance.cards.application.usecase.CreateCard;
import com.smarthealthfinance.cards.application.usecase.GetCard;
import com.smarthealthfinance.cards.application.usecase.GetCardUtilization;
import com.smarthealthfinance.cards.application.usecase.GetCardsSummary;
import com.smarthealthfinance.cards.application.usecase.GetFutureInvoices;
import com.smarthealthfinance.cards.application.usecase.GetInstallmentPlans;
import com.smarthealthfinance.cards.application.usecase.GetInvoice;
import com.smarthealthfinance.cards.application.usecase.ListCards;
import com.smarthealthfinance.cards.application.usecase.ListInvoiceItems;
import com.smarthealthfinance.cards.application.usecase.ListInvoices;
import com.smarthealthfinance.cards.application.usecase.ReactivateCard;
import com.smarthealthfinance.cards.application.usecase.RecordCardCredit;
import com.smarthealthfinance.cards.application.usecase.RecordCardPayment;
import com.smarthealthfinance.cards.application.usecase.RecordCardPurchase;
import com.smarthealthfinance.cards.application.usecase.ReconcileCardPayment;
import com.smarthealthfinance.cards.application.usecase.UpdateCard;
import com.smarthealthfinance.cards.presentation.dto.request.CardCreditRequest;
import com.smarthealthfinance.cards.presentation.dto.request.CardPaymentReconciliationRequest;
import com.smarthealthfinance.cards.presentation.dto.request.CardPaymentRequest;
import com.smarthealthfinance.cards.presentation.dto.request.CardPurchaseRequest;
import com.smarthealthfinance.cards.presentation.dto.request.CardRequest;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.CardListResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.CardPaymentReconciliationResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.CardPurchaseResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.CardResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.CardTransactionResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.CardsSummaryResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.FutureInvoicesResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.InstallmentPlanListResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.InstallmentPlanResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.InvoiceItemPageResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.InvoiceListResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.InvoiceResponse;
import com.smarthealthfinance.cards.presentation.dto.response.CardResponses.UtilizationResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction;
import com.smarthealthfinance.transactions.application.usecase.ReclassifyAsCardPayment;
import com.smarthealthfinance.transactions.presentation.dto.response.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** Cartões e faturas (ADR-0025, ADR-0026). Nenhum dado do cartão além dos 4 últimos dígitos entra ou sai. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/cards")
@Tag(name = "Cards")
public class CardController {

    private static final String MONTH = "Mês de vencimento da fatura (YYYY-MM)";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ListCards listCards;
    private final GetCard getCard;
    private final CreateCard createCard;
    private final UpdateCard updateCard;
    private final ArchiveCard archiveCard;
    private final ReactivateCard reactivateCard;
    private final GetCardsSummary getSummary;
    private final ListInvoices listInvoices;
    private final GetInvoice getInvoice;
    private final ListInvoiceItems listItems;
    private final GetFutureInvoices getFuture;
    private final GetCardUtilization getUtilization;
    private final RecordCardPurchase recordPurchase;
    private final RecordCardPayment recordPayment;
    private final RecordCardCredit recordCredit;
    private final GetInstallmentPlans installmentPlans;
    private final ReconcileCardPayment reconcilePayment;

    public CardController(ListCards listCards, GetCard getCard, CreateCard createCard, UpdateCard updateCard,
                          ArchiveCard archiveCard, ReactivateCard reactivateCard, GetCardsSummary getSummary,
                          ListInvoices listInvoices, GetInvoice getInvoice, ListInvoiceItems listItems,
                          GetFutureInvoices getFuture, GetCardUtilization getUtilization,
                          RecordCardPurchase recordPurchase, RecordCardPayment recordPayment,
                          RecordCardCredit recordCredit, GetInstallmentPlans installmentPlans,
                          ReconcileCardPayment reconcilePayment) {
        this.reconcilePayment = reconcilePayment;
        this.recordPurchase = recordPurchase;
        this.recordPayment = recordPayment;
        this.recordCredit = recordCredit;
        this.installmentPlans = installmentPlans;
        this.listCards = listCards;
        this.getCard = getCard;
        this.createCard = createCard;
        this.updateCard = updateCard;
        this.archiveCard = archiveCard;
        this.reactivateCard = reactivateCard;
        this.getSummary = getSummary;
        this.listInvoices = listInvoices;
        this.getInvoice = getInvoice;
        this.listItems = listItems;
        this.getFuture = getFuture;
        this.getUtilization = getUtilization;
    }

    @GetMapping
    @Operation(summary = "Lista os cartões do Workspace (arquivados somente com includeArchived=true)")
    @ApiResponse(responseCode = "200", description = "Cartões em ordem de criação")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CardListResponse listCards(@PathVariable UUID workspaceId,
                                 @RequestParam(defaultValue = "false") boolean includeArchived) {
        return CardListResponse.from(listCards.execute(workspaceId, includeArchived));
    }

    @PostMapping
    @Operation(summary = "Cadastra um cartão (somente os 4 últimos dígitos; campos desconhecidos são recusados)")
    @ApiResponse(responseCode = "201", description = "Cartão criado")
    @ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST (campo desconhecido) ou VALIDATION_FAILED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou ACCOUNT_NOT_FOUND (conta padrão)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "ACCOUNT_ARCHIVED (conta padrão)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CardResponse> createCard(@PathVariable UUID workspaceId,
                                               @Valid @RequestBody CardRequest request) {
        CardView view = createCard.execute(workspaceId, new CreateCard.Command(request.nickname(),
                request.institutionName(), request.brand(), request.last4(), request.limit().amount(),
                request.limit().currency(), request.closingDay(), request.dueDay(),
                request.defaultPaymentAccountId()));

        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/cards/" + view.id()))
                .body(CardResponse.from(view));
    }

    @GetMapping("/summary")
    @Operation(summary = "Resumo dos cartões ativos: totais, fatura atual, utilização e próxima fatura")
    @ApiResponse(responseCode = "200", description = "Resumo calculado")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CardsSummaryResponse cardsSummary(@PathVariable UUID workspaceId) {
        return CardsSummaryResponse.from(getSummary.execute(workspaceId));
    }

    @GetMapping("/{cardId}")
    @Operation(summary = "Consulta um cartão do Workspace")
    @ApiResponse(responseCode = "200", description = "Cartão encontrado")
    @ApiResponse(responseCode = "404",
            description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND (inexistente ou de outro Workspace)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CardResponse getCard(@PathVariable UUID workspaceId, @PathVariable UUID cardId) {
        return CardResponse.from(getCard.execute(workspaceId, cardId));
    }

    @PutMapping("/{cardId}")
    @Operation(summary = "Edita o cartão (substituição completa; mudar o ciclo recalcula as faturas não fechadas)")
    @ApiResponse(responseCode = "200", description = "Cartão atualizado")
    @ApiResponse(responseCode = "400",
            description = "MALFORMED_REQUEST ou VALIDATION_FAILED (inclusive dueDay DUE_MONTH_CONFLICT)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND, CARD_NOT_FOUND ou ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CARD_ARCHIVED, ACCOUNT_ARCHIVED ou CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CardResponse updateCard(@PathVariable UUID workspaceId, @PathVariable UUID cardId,
                               @Valid @RequestBody CardRequest request) {
        return CardResponse.from(updateCard.execute(workspaceId, cardId, new UpdateCard.Command(request.nickname(),
                request.institutionName(), request.brand(), request.last4(), request.limit().amount(),
                request.limit().currency(), request.closingDay(), request.dueDay(),
                request.defaultPaymentAccountId())));
    }

    @PostMapping("/{cardId}/archive")
    @Operation(summary = "Arquiva o cartão (histórico mantido; idempotente)")
    @ApiResponse(responseCode = "200", description = "Cartão arquivado")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CardResponse archiveCard(@PathVariable UUID workspaceId, @PathVariable UUID cardId) {
        return CardResponse.from(archiveCard.execute(workspaceId, cardId));
    }

    @PostMapping("/{cardId}/reactivate")
    @Operation(summary = "Reativa um cartão arquivado (idempotente)")
    @ApiResponse(responseCode = "200", description = "Cartão ativo")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CardResponse reactivateCard(@PathVariable UUID workspaceId, @PathVariable UUID cardId) {
        return CardResponse.from(reactivateCard.execute(workspaceId, cardId));
    }

    @GetMapping("/{cardId}/invoices")
    @Operation(summary = "Faturas com lançamentos no intervalo, mais a atual (padrão: 6 meses; máximo 24)")
    @ApiResponse(responseCode = "200", description = "Faturas da mais recente para a mais antiga")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (from/to)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InvoiceListResponse listCardInvoices(@PathVariable UUID workspaceId, @PathVariable UUID cardId,
                                        @Parameter(description = MONTH) @RequestParam(required = false) String from,
                                        @Parameter(description = MONTH) @RequestParam(required = false) String to) {
        return InvoiceListResponse.from(listInvoices.execute(workspaceId, cardId, from, to));
    }

    @GetMapping("/{cardId}/invoices/current")
    @Operation(summary = "Fatura atual (período contém hoje): parcial, pode mudar até o fechamento")
    @ApiResponse(responseCode = "200", description = "Fatura atual")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InvoiceResponse getCurrentCardInvoice(@PathVariable UUID workspaceId, @PathVariable UUID cardId) {
        return InvoiceResponse.from(getInvoice.current(workspaceId, cardId));
    }

    @GetMapping("/{cardId}/invoices/future")
    @Operation(summary = "Faturas futuras com o valor já comprometido em parcelas (sem estimativas)")
    @ApiResponse(responseCode = "200", description = "Faturas futuras comprometidas")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public FutureInvoicesResponse getFutureCardInvoices(@PathVariable UUID workspaceId, @PathVariable UUID cardId) {
        return FutureInvoicesResponse.from(getFuture.execute(workspaceId, cardId));
    }

    @GetMapping("/{cardId}/invoices/{dueMonth}")
    @Operation(summary = "Fatura pelo mês de vencimento")
    @ApiResponse(responseCode = "200", description = "Fatura encontrada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (dueMonth)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404",
            description = "WORKSPACE_NOT_FOUND, CARD_NOT_FOUND ou INVOICE_NOT_FOUND (fatura passada sem lançamentos)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InvoiceResponse getCardInvoice(@PathVariable UUID workspaceId, @PathVariable UUID cardId,
                                   @Parameter(description = MONTH) @PathVariable String dueMonth) {
        return InvoiceResponse.from(getInvoice.byDueMonth(workspaceId, cardId, dueMonth));
    }

    @GetMapping("/{cardId}/invoices/{dueMonth}/items")
    @Operation(summary = "Lançamentos da fatura, paginados (pageSize ≤ 100, padrão 50)")
    @ApiResponse(responseCode = "200", description = "Página de lançamentos")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (dueMonth, page, pageSize)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND, CARD_NOT_FOUND ou INVOICE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InvoiceItemPageResponse listCardInvoiceItems(@PathVariable UUID workspaceId, @PathVariable UUID cardId,
                                                @Parameter(description = MONTH) @PathVariable String dueMonth,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer pageSize) {
        return InvoiceItemPageResponse.from(listItems.execute(workspaceId, cardId, dueMonth, page,
                pageSize));
    }

    @PostMapping("/{cardId}/purchases")
    @Operation(summary = "Compra no cartão, à vista ou parcelada (idempotente por Idempotency-Key)")
    @ApiResponse(responseCode = "201", description = "Compra registrada com as parcelas")
    @ApiResponse(responseCode = "200", description = "Replay: a mesma chave e conteúdo já foram processados")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (installmentCount OUT_OF_RANGE/TOO_MANY_FOR_AMOUNT, Idempotency-Key)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CARD_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CardPurchaseResponse> recordPurchase(
            @PathVariable UUID workspaceId, @PathVariable UUID cardId,
            @Parameter(description = "Chave única por intenção", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CardPurchaseRequest request) {
        CardPurchaseView view = recordPurchase.execute(workspaceId, cardId, new RecordCardPurchase.Command(
                idempotencyKey, request.amount().amount(), request.amount().currency(), request.occurredOn(),
                request.description(), request.installmentCount()));
        CardPurchaseResponse body = CardPurchaseResponse.from(view);
        return view.replayed() ? ResponseEntity.ok(body) : created(workspaceId, view.transaction().id(), body);
    }

    @PostMapping("/{cardId}/payments")
    @Operation(summary = "Pagamento de fatura, total ou parcial: transferência da conta para o cartão (idempotente)")
    @ApiResponse(responseCode = "201", description = "Pagamento registrado")
    @ApiResponse(responseCode = "200", description = "Replay: a mesma chave e conteúdo já foram processados")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND, CARD_NOT_FOUND ou ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CARD_ARCHIVED ou ACCOUNT_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CardTransactionResponse> recordPayment(
            @PathVariable UUID workspaceId, @PathVariable UUID cardId,
            @Parameter(description = "Chave única por intenção", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CardPaymentRequest request) {
        CreateTransaction.Result result = recordPayment.execute(workspaceId, cardId, new RecordCardPayment.Command(
                idempotencyKey, request.accountId(), request.amount().amount(), request.amount().currency(),
                request.occurredOn(), request.description()));
        return transactionResult(workspaceId, result);
    }

    @PostMapping("/{cardId}/credits")
    @Operation(summary = "Estorno ou crédito no cartão, opcionalmente vinculado a uma compra dele (idempotente)")
    @ApiResponse(responseCode = "201", description = "Estorno registrado")
    @ApiResponse(responseCode = "200", description = "Replay: a mesma chave e conteúdo já foram processados")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (refundOfTransactionId NOT_FOUND/NOT_SAME_CARD/NOT_REFUNDABLE, amount "
                    + "EXCEEDS_REFUNDABLE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CARD_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CardTransactionResponse> recordCredit(
            @PathVariable UUID workspaceId, @PathVariable UUID cardId,
            @Parameter(description = "Chave única por intenção", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CardCreditRequest request) {
        CreateTransaction.Result result = recordCredit.execute(workspaceId, cardId, new RecordCardCredit.Command(
                idempotencyKey, request.amount().amount(), request.amount().currency(), request.occurredOn(),
                request.description(), request.refundOfTransactionId()));
        return transactionResult(workspaceId, result);
    }

    @PostMapping("/{cardId}/payments/reconcile")
    @Operation(summary = "Marca um débito importado na conta como pagamento da fatura deste cartão (idempotente)")
    @ApiResponse(responseCode = "201", description = "Despesa estornada e transferência para o cartão criada")
    @ApiResponse(responseCode = "200",
            description = "Despesa vinculada a um pagamento já registrado (matchedExisting) ou replay")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (transactionId NOT_RECLASSIFIABLE ou ALREADY_RECLASSIFIED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND, CARD_NOT_FOUND ou TRANSACTION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409",
            description = "CARD_ARCHIVED, ACCOUNT_ARCHIVED ou TRANSACTION_STATUS_CONFLICT (despesa não lançada)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CardPaymentReconciliationResponse> reconcilePayment(
            @PathVariable UUID workspaceId, @PathVariable UUID cardId,
            @Valid @RequestBody CardPaymentReconciliationRequest request) {
        ReclassifyAsCardPayment.Result result = reconcilePayment.execute(workspaceId, cardId,
                request.transactionId());
        CardPaymentReconciliationResponse body = new CardPaymentReconciliationResponse(
                TransactionResponse.from(result.payment()), result.matchedExisting());
        return result.replayed() || result.matchedExisting() ? ResponseEntity.ok(body)
                : created(workspaceId, result.payment().id(), body);
    }

    @GetMapping("/{cardId}/installments")
    @Operation(summary = "Parcelamentos do cartão (compras em mais de uma parcela)")
    @ApiResponse(responseCode = "200", description = "Parcelamentos, da compra mais recente para a mais antiga")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (status)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InstallmentPlanListResponse listInstallmentPlans(
            @PathVariable UUID workspaceId, @PathVariable UUID cardId,
            @Parameter(description = "ACTIVE (padrão), FINISHED ou ALL")
            @RequestParam(required = false) String status) {
        return InstallmentPlanListResponse.from(installmentPlans.list(workspaceId, cardId, status));
    }

    @GetMapping("/{cardId}/installments/{transactionId}")
    @Operation(summary = "Parcelamento de uma compra do cartão, com cada parcela e o limite reservado")
    @ApiResponse(responseCode = "200", description = "Parcelamento")
    @ApiResponse(responseCode = "404",
            description = "WORKSPACE_NOT_FOUND, CARD_NOT_FOUND ou TRANSACTION_NOT_FOUND (não é compra deste cartão)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InstallmentPlanResponse getInstallmentPlan(@PathVariable UUID workspaceId, @PathVariable UUID cardId,
                                                      @PathVariable UUID transactionId) {
        return InstallmentPlanResponse.from(installmentPlans.get(workspaceId, cardId, transactionId));
    }

    private static ResponseEntity<CardTransactionResponse> transactionResult(UUID workspaceId,
                                                                             CreateTransaction.Result result) {
        CardTransactionResponse body = new CardTransactionResponse(TransactionResponse.from(result.transaction()));
        return result.replayed() ? ResponseEntity.ok(body) : created(workspaceId, result.transaction().id(), body);
    }

    private static <T> ResponseEntity<T> created(UUID workspaceId, UUID transactionId, T body) {
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/transactions/"
                + transactionId)).body(body);
    }

    @GetMapping("/{cardId}/utilization")
    @Operation(summary = "Utilização do limite: fatura atual, faturas anteriores em aberto e parcelas futuras")
    @ApiResponse(responseCode = "200", description = "Utilização calculada")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UtilizationResponse getCardUtilization(@PathVariable UUID workspaceId, @PathVariable UUID cardId) {
        return UtilizationResponse.from(getUtilization.execute(workspaceId, cardId));
    }
}
