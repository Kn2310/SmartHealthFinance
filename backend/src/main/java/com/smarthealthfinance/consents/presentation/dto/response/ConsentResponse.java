package com.smarthealthfinance.consents.presentation.dto.response;

import com.smarthealthfinance.consents.application.dto.ConsentView;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record ConsentResponse(
        @Schema(allowableValues = {"COPILOT_DATA_USE", "COPILOT_HISTORY"}) String purpose,
        @Schema(allowableValues = {"PENDING", "ACTIVE", "EXPIRED", "REVOKED", "FAILED"}) String state,
        @Schema(description = "Versão do termo concedida por último; nula se nunca concedido") String termsVersion,
        @Schema(description = "Versão vigente do termo, a enviar para conceder") String currentTermsVersion,
        Instant grantedAt,
        Instant revokedAt,
        @Schema(description = "Finalidades que precisam estar ativas para conceder esta") List<String> requires) {

    public static ConsentResponse from(ConsentView view) {
        return new ConsentResponse(view.purpose().name(), view.state().name(), view.termsVersion(),
                view.currentTermsVersion(), view.grantedAt(), view.revokedAt(),
                view.requires().stream().map(ConsentPurpose::name).toList());
    }
}
