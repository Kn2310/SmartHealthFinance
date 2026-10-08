package com.smarthealthfinance.ingestion.application.port;

import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.model.ParsedStatement;

/**
 * Lê um extrato de um formato (ADR-0009). Implementações são adapters puros: não acessam banco nem rede.
 */
public interface StatementParser {

    ImportFormat format();

    /**
     * @param content bytes do arquivo enviado (já limitado em tamanho pela API)
     * @throws com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException se o arquivo inteiro
     *         for inválido
     */
    ParsedStatement parse(byte[] content);
}
