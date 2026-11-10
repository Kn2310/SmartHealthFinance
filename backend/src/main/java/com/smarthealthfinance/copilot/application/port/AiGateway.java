package com.smarthealthfinance.copilot.application.port;

/**
 * Porta do AI Gateway (ADR-0039 §2), independente do SDK. O modelo de cada método vem da configuração: intenção no
 * modelo leve, resposta no mais capaz. Nunca lança por falha do provedor: o resultado traz um {@code outcome} tipado.
 * Quem chama não pode estar dentro de uma transação de banco.
 */
public interface AiGateway {

    AiResult classify(AiRequest request);

    AiResult answer(AiRequest request);
}
