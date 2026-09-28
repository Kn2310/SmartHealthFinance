package com.smarthealthfinance.shared.infrastructure.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração de segurança da API.
 *
 * @param corsAllowedOrigins origens explicitamente permitidas (CORS restritivo — sem curingas)
 * @param publicApiDocs expõe OpenAPI/Swagger UI sem autenticação (apenas em ambientes não produtivos)
 */
@ConfigurationProperties("shf.security")
public record SecurityProperties(@DefaultValue List<String> corsAllowedOrigins,
		@DefaultValue("false") boolean publicApiDocs) {
}
