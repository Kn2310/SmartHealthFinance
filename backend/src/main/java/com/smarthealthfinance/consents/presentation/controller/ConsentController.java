package com.smarthealthfinance.consents.presentation.controller;

import com.smarthealthfinance.consents.application.usecase.ChangeConsent;
import com.smarthealthfinance.consents.application.usecase.ListConsents;
import com.smarthealthfinance.consents.presentation.dto.request.ChangeConsentRequest;
import com.smarthealthfinance.consents.presentation.dto.response.ConsentListResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Consentimentos da própria pessoa no Workspace (ADR-0038 §7). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/consents")
@Tag(name = "Consents")
public class ConsentController {

    private final ListConsents listConsents;
    private final ChangeConsent changeConsent;

    public ConsentController(ListConsents listConsents, ChangeConsent changeConsent) {
        this.listConsents = listConsents;
        this.changeConsent = changeConsent;
    }

    @GetMapping
    @Operation(summary = "Lista os consentimentos da pessoa no Workspace (os nunca respondidos como PENDING)")
    @ApiResponse(responseCode = "200", description = "Todas as finalidades")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ConsentListResponse list(@PathVariable UUID workspaceId) {
        return ConsentListResponse.from(listConsents.execute(workspaceId));
    }

    @PutMapping("/{purpose}")
    @Operation(summary = "Concede ou revoga um consentimento (idempotente; revogar o uso de dados revoga o histórico)")
    @ApiResponse(responseCode = "200", description = "Todas as finalidades depois da mudança")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CONSENT_PURPOSE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409",
            description = "CONSENT_TERMS_OUTDATED, CONSENT_PREREQUISITE_MISSING ou CONFLICT (mudança concorrente)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ConsentListResponse change(@PathVariable UUID workspaceId, @PathVariable String purpose,
                                      @Valid @RequestBody ChangeConsentRequest request) {
        return ConsentListResponse.from(changeConsent.execute(workspaceId, purpose,
                new ChangeConsent.Command(request.granted(), request.termsVersion())));
    }
}
