package com.smarthealthfinance.copilot.domain.service;

/**
 * Título de uma conversa guardada (ADR-0040 §6): a primeira pergunta cortada em 80 caracteres sem quebrar palavra,
 * com reticências quando cortou. Determinístico, sem texto do modelo.
 */
public final class ConversationTitle {

    public static final int MAX_CHARS = 80;
    static final String ELLIPSIS = "…";

    private ConversationTitle() {
    }

    public static String from(String question) {
        String text = question == null ? "" : question.strip().replaceAll("\\s+", " ");
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        int end = MAX_CHARS;
        // A palavra que atravessa o limite sai inteira; sem espaço para cortar, corta no limite.
        if (!Character.isWhitespace(text.charAt(end))) {
            int lastSpace = text.lastIndexOf(' ', end);
            if (lastSpace > 0) {
                end = lastSpace;
            } else if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
        }
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }
}
