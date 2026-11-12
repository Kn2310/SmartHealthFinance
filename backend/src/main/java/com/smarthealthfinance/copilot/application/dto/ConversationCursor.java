package com.smarthealthfinance.copilot.application.dto;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Cursor opaco da listagem de conversas (ADR-0040 §2): {@code last_message_at} e id da última conversa da página. Só
 * tempo e id: nunca conteúdo.
 */
public record ConversationCursor(Instant lastMessageAt, UUID id) {

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((lastMessageAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidValueException {@code before} {@code INVALID} para um cursor que não é nosso */
    public static ConversationCursor parse(String value) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", 2);
            if (parts.length != 2) {
                throw new InvalidValueException("before", "INVALID");
            }
            return new ConversationCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            throw new InvalidValueException("before", "INVALID");
        }
    }
}
