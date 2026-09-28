package com.smarthealthfinance.shared.presentation.error;

import java.util.List;

/**
 * Contrato de erro da API (specs 05.6): código estável + mensagem + detalhes + traceId.
 */
public record ApiError(String code, String message, List<Detail> details, String traceId) {

	public ApiError {
		details = details == null ? List.of() : List.copyOf(details);
	}

	/** Detalhe de um erro, normalmente associado a um campo da requisição. */
	public record Detail(String field, String code, String message) {
	}

}
