package com.smarthealthfinance.cards.presentation.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.cards.domain.valueobject.CardNickname;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * Cadastro e edição do cartão (substituição completa, ADR-0026 §2). {@code brand}, {@code last4}, o limite e os dias
 * são validados pelo domínio.
 * <p>
 * Campo desconhecido recusa a requisição inteira ({@code 400 MALFORMED_REQUEST}): número completo, CVV ou validade
 * nunca são aceitos, nem descartados em silêncio (ADR-0025 §1).
 */
public record CardRequest(
        @NotBlank @Size(max = CardNickname.MAX_LENGTH) String nickname,
        @Size(max = InstitutionName.MAX_LENGTH) String institutionName,
        @NotBlank String brand,
        @Schema(description = "Somente os 4 últimos dígitos do cartão", example = "4821") @NotBlank String last4,
        @NotNull @Valid MoneyDto limit,
        @Schema(minimum = "1", maximum = "31") @NotNull Integer closingDay,
        @Schema(minimum = "1", maximum = "31") @NotNull Integer dueDay,
        UUID defaultPaymentAccountId,
        @Schema(hidden = true) @JsonAnySetter Map<String, Object> unknownFields) {

    public CardRequest {
        if (unknownFields != null && !unknownFields.isEmpty()) {
            throw new IllegalArgumentException("Campos desconhecidos no cartão");
        }
    }
}
