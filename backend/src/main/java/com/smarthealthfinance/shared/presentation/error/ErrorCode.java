package com.smarthealthfinance.shared.presentation.error;

import org.springframework.http.HttpStatus;

/**
 * Códigos de erro estáveis expostos pela API (specs 05.6).
 * Clientes devem depender do código, nunca da mensagem.
 */
public enum ErrorCode {

	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "A requisição não pôde ser interpretada."),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "A requisição contém dados inválidos."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Autenticação necessária."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "Acesso negado."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "Recurso não encontrado."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido para este recurso."),
	/** 409 genérico: precisa vir antes de qualquer 409 específico por causa de {@link #fromStatus}. */
	CONFLICT(HttpStatus.CONFLICT, "O recurso foi alterado por outra requisição ou está em estado incompatível."),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Formato de resposta não suportado."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de conteúdo não suportado."),
	PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Requisição excede o tamanho permitido."),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Serviço temporariamente indisponível."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno inesperado."),
	USER_NOT_PROVISIONED(HttpStatus.NOT_FOUND, "Usuário ainda não provisionado."),
	USER_DISABLED(HttpStatus.FORBIDDEN, "Usuário desativado."),
	IDENTITY_CLAIMS_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, "O token não contém os dados de identidade necessários."),
	/** Inexistente ou sem membership: indistinguíveis de propósito (ADR-0003). */
	WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "Workspace não encontrado."),
	/** Inexistente ou de outro Workspace: indistinguíveis de propósito (ADR-0004). */
	ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Conta não encontrada."),
	ACCOUNT_ARCHIVED(HttpStatus.CONFLICT, "Conta arquivada não pode ser alterada."),
	/** Inexistente ou de outro Workspace: indistinguíveis de propósito (ADR-0005). */
	TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transação não encontrada."),
	TRANSACTION_STATUS_CONFLICT(HttpStatus.CONFLICT, "A transação não permite esta mudança de status."),
	/** Inexistente ou de outro Workspace: indistinguíveis de propósito (ADR-0009). */
	IMPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "Importação não encontrada."),
	IMPORT_STATUS_CONFLICT(HttpStatus.CONFLICT, "A importação não permite esta ação no status atual."),
	/** O arquivo inteiro foi recusado; o motivo vai em {@code details[0].code} (ADR-0009 §7). */
	IMPORT_FILE_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "O arquivo não pôde ser importado."),
	/** Mesma Idempotency-Key com conteúdo diferente (spec 05.6). */
	IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT,
			"A Idempotency-Key já foi usada em uma requisição com outro conteúdo.");

	private final HttpStatus status;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}

	/** Código genérico para um status HTTP sem mapeamento específico. */
	public static ErrorCode fromStatus(int status) {
		for (ErrorCode code : values()) {
			if (code.status.value() == status) {
				return code;
			}
		}
		return status >= 500 ? INTERNAL_ERROR : MALFORMED_REQUEST;
	}

}
