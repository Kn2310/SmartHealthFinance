package com.smarthealthfinance.accounts.presentation.dto.request;

import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code includedInTotal} ausente assume true; {@code type} é validado pelo domínio (AccountType). */
public record CreateAccountRequest(
        @NotBlank @Size(max = AccountName.MAX_LENGTH) String name,
        @NotBlank String type,
        @Size(max = InstitutionName.MAX_LENGTH) String institutionName,
        Boolean includedInTotal) {
}
