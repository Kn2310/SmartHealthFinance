package com.smarthealthfinance.copilot.application.dto;

import java.time.Instant;
import java.util.UUID;

/** Uma conversa na listagem (ADR-0040 §2); o título é derivado da primeira pergunta. */
public record ConversationSummary(UUID id, String title, Instant createdAt, Instant lastMessageAt,
                                  int messageCount) {

    /** Sem o título: vem da pergunta da pessoa. */
    @Override
    public String toString() {
        return "ConversationSummary[" + id + ", " + messageCount + " messages]";
    }
}
