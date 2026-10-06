package com.smarthealthfinance.accounts.presentation.controller;

import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.usecase.ArchiveAccount;
import com.smarthealthfinance.accounts.application.usecase.CreateAccount;
import com.smarthealthfinance.accounts.application.usecase.GetAccount;
import com.smarthealthfinance.accounts.application.usecase.ListAccounts;
import com.smarthealthfinance.accounts.application.usecase.ReactivateAccount;
import com.smarthealthfinance.accounts.application.usecase.UpdateAccount;
import com.smarthealthfinance.accounts.presentation.dto.request.CreateAccountRequest;
import com.smarthealthfinance.accounts.presentation.dto.request.UpdateAccountRequest;
import com.smarthealthfinance.accounts.presentation.dto.response.AccountListResponse;
import com.smarthealthfinance.accounts.presentation.dto.response.AccountResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final ListAccounts listAccounts;
    private final GetAccount getAccount;
    private final CreateAccount createAccount;
    private final UpdateAccount updateAccount;
    private final ArchiveAccount archiveAccount;
    private final ReactivateAccount reactivateAccount;

    public AccountController(ListAccounts listAccounts, GetAccount getAccount, CreateAccount createAccount,
                             UpdateAccount updateAccount, ArchiveAccount archiveAccount,
                             ReactivateAccount reactivateAccount) {
        this.listAccounts = listAccounts;
        this.getAccount = getAccount;
        this.createAccount = createAccount;
        this.updateAccount = updateAccount;
        this.archiveAccount = archiveAccount;
        this.reactivateAccount = reactivateAccount;
    }

    @GetMapping
    @Operation(summary = "Lista as contas do Workspace (arquivadas somente com includeArchived=true)")
    @ApiResponse(responseCode = "200", description = "Contas do Workspace em ordem de criação")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AccountListResponse list(@PathVariable UUID workspaceId,
                                    @RequestParam(defaultValue = "false") boolean includeArchived) {
        return new AccountListResponse(
                listAccounts.execute(workspaceId, includeArchived).stream().map(AccountResponse::from).toList());
    }

    @PostMapping
    @Operation(summary = "Cria uma conta manual no Workspace")
    @ApiResponse(responseCode = "201", description = "Conta criada")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AccountResponse> create(@PathVariable UUID workspaceId,
                                                  @Valid @RequestBody CreateAccountRequest request) {
        AccountView view = createAccount.execute(workspaceId, new CreateAccount.Command(request.name(),
                request.type(), request.institutionName(), request.includedInTotal()));

        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/accounts/" + view.id()))
                .body(AccountResponse.from(view));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Consulta uma conta do Workspace")
    @ApiResponse(responseCode = "200", description = "Conta encontrada")
    @ApiResponse(responseCode = "404",
            description = "WORKSPACE_NOT_FOUND ou ACCOUNT_NOT_FOUND (inexistente ou de outro Workspace)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AccountResponse get(@PathVariable UUID workspaceId, @PathVariable UUID accountId) {
        return AccountResponse.from(getAccount.execute(workspaceId, accountId));
    }

    @PutMapping("/{accountId}")
    @Operation(summary = "Edita nome, tipo, instituição e inclusão no saldo total")
    @ApiResponse(responseCode = "200", description = "Conta atualizada")
    @ApiResponse(responseCode = "409", description = "ACCOUNT_ARCHIVED ou CONFLICT (edição concorrente)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AccountResponse update(@PathVariable UUID workspaceId, @PathVariable UUID accountId,
                                  @Valid @RequestBody UpdateAccountRequest request) {
        return AccountResponse.from(updateAccount.execute(workspaceId, accountId, new UpdateAccount.Command(
                request.name(), request.type(), request.institutionName(), request.includedInTotal())));
    }

    @PostMapping("/{accountId}/archive")
    @Operation(summary = "Arquiva a conta (histórico mantido; idempotente)")
    public AccountResponse archive(@PathVariable UUID workspaceId, @PathVariable UUID accountId) {
        return AccountResponse.from(archiveAccount.execute(workspaceId, accountId));
    }

    @PostMapping("/{accountId}/reactivate")
    @Operation(summary = "Reativa uma conta arquivada (idempotente)")
    public AccountResponse reactivate(@PathVariable UUID workspaceId, @PathVariable UUID accountId) {
        return AccountResponse.from(reactivateAccount.execute(workspaceId, accountId));
    }
}
