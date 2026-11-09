package com.smarthealthfinance.consents.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code termsVersion} é obrigatória para conceder (a vigente) e ignorada para revogar (ADR-0038 §3, §7). */
public record ChangeConsentRequest(
        @NotNull Boolean granted,
        @Size(max = 40) String termsVersion) {
}
