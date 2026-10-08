package com.smarthealthfinance.ingestion.infrastructure.parser;

import com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodifica o arquivo enviado (ADR-0009 §5/§6).
 * <p>
 * Ordem: BOM (UTF-8/UTF-16) → UTF-8 estrito → charset declarado pelo arquivo → Windows-1252.
 * UTF-8 válido vence o charset declarado porque há bancos que declaram 1252 e enviam UTF-8; o inverso não
 * acontece por acaso, já que texto 1252 com acentos quase nunca é UTF-8 válido.
 */
final class StatementText {

    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private StatementText() {
    }

    static String decode(byte[] content, Charset declared) {
        if (content == null || content.length == 0) {
            throw new StatementRejectedException("EMPTY_FILE");
        }

        String text = decodeWithBomOrFallback(content, declared);

        // Arquivo binário (planilha .xlsx renomeada, imagem...) não é extrato.
        if (text.indexOf('\0') >= 0) {
            throw new StatementRejectedException("UNREADABLE_FILE");
        }
        if (text.isBlank()) {
            throw new StatementRejectedException("EMPTY_FILE");
        }
        return text;
    }

    private static String decodeWithBomOrFallback(byte[] content, Charset declared) {
        if (startsWith(content, 0xEF, 0xBB, 0xBF)) {
            return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(content, 0xFF, 0xFE)) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(content, 0xFE, 0xFF)) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        }
        catch (CharacterCodingException notUtf8) {
            Charset fallback = declared == null || StandardCharsets.UTF_8.equals(declared) ? WINDOWS_1252 : declared;
            return new String(content, fallback);
        }
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
