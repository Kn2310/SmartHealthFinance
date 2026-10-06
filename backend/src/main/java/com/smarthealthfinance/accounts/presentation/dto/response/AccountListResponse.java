package com.smarthealthfinance.accounts.presentation.dto.response;

import java.util.List;

/** Envelope para permitir paginação futura sem quebrar o contrato (specs 05.6). */
public record AccountListResponse(List<AccountResponse> items) {
}
