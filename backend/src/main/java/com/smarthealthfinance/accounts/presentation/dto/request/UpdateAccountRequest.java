package com.smarthealthfinance.accounts.presentation.dto.request;

import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Substituição completa dos campos editáveis (modal "Editar conta"). */
public record UpdateAccountRequest(
        @NotBlank @Size(max = AccountName.MAX_LENGTH) String name,
        @NotBlank String type,
        @Size(max = InstitutionName.MAX_LENGTH) String institutionName,
        @NotNull Boolean includedInTotal) {
}
