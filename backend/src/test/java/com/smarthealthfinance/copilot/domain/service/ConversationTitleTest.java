package com.smarthealthfinance.copilot.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Título determinístico da conversa (ADR-0040 §6): 80 caracteres sem quebrar palavra, com reticências. */
class ConversationTitleTest {

    @Test
    void aShortQuestionIsTheTitleAsIs() {
        assertThat(ConversationTitle.from("  Por que minha   fatura\nsubiu?  ")).isEqualTo("Por que minha fatura subiu?");
        assertThat(ConversationTitle.from("x".repeat(80))).isEqualTo("x".repeat(80));
    }

    @Test
    void aLongQuestionIsCutAtTheLastWordThatFitsWithEllipsis() {
        String question = "Explique em detalhes por que o meu gasto total do mês passado foi maior do que a média "
                + "dos meses anteriores?";

        String title = ConversationTitle.from(question);

        assertThat(title).isEqualTo("Explique em detalhes por que o meu gasto total do mês passado foi maior do que a…");
        assertThat(title.length()).isLessThanOrEqualTo(ConversationTitle.MAX_CHARS + 1);
    }

    @Test
    void theWordCrossingTheLimitLeavesWholeAndALimitOnASpaceKeepsTheWord() {
        String crossing = "a".repeat(70) + " " + "palavragrande".repeat(2);
        assertThat(ConversationTitle.from(crossing)).isEqualTo("a".repeat(70) + "…");

        String exact = "b".repeat(79) + " " + "c".repeat(30);
        assertThat(ConversationTitle.from(exact)).isEqualTo("b".repeat(79) + "…");
    }

    @Test
    void withoutAnySpaceItCutsAtTheLimitAndNeverSplitsASurrogatePair() {
        assertThat(ConversationTitle.from("z".repeat(200))).isEqualTo("z".repeat(80) + "…");

        String emojis = "a" + "😀".repeat(60);
        String title = ConversationTitle.from(emojis);
        assertThat(title).endsWith("…");
        assertThat(title.substring(0, title.length() - 1)).matches("a(😀)+");
    }
}
