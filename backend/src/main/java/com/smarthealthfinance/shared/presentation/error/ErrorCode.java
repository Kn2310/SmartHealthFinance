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
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Formato de resposta não suportado."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de conteúdo não suportado."),
	PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Requisição excede o tamanho permitido."),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Serviço temporariamente indisponível."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno inesperado."),
	USER_NOT_PROVISIONED(HttpStatus.NOT_FOUND, "Usuário ainda não provisionado."),
	USER_DISABLED(HttpStatus.FORBIDDEN, "Usuário desativado."),
	IDENTITY_CLAIMS_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, "O token não contém os dados de identidade necessários."),
	/** Inexistente ou sem membership: indistinguíveis de propósito (ADR-0003). */
	WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "Workspace não encontrado.");

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
