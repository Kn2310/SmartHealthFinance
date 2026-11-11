package com.smarthealthfinance.copilot.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Uma conversa guardada com as mensagens não expiradas, da mais antiga para a mais nova. */
public record ConversationDetail(UUID id, String title, Instant createdAt, List<StoredMessage> messages) {

    public ConversationDetail {
        messages = List.copyOf(messages);
    }

    /** Sem o título nem as mensagens. */
    @Override
    public String toString() {
        return "ConversationDetail[" + id + ", " + messages.size() + " messages]";
    }
}
