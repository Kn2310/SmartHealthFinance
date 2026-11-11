package com.smarthealthfinance.copilot.application.dto;

import java.util.List;

/** Página de conversas; {@code historyEnabled} é falso sem {@code COPILOT_HISTORY}, e então {@code items} é vazio. */
public record ConversationPage(boolean historyEnabled, List<ConversationSummary> items, String nextCursor) {

    public ConversationPage {
        items = List.copyOf(items);
    }
}
