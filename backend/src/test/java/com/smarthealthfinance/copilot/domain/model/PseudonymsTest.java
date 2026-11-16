package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pseudônimos de comerciante e a marca "citado na pergunta" (adendo de 2026-11-16 ao ADR-0039 §3). */
class PseudonymsTest {

    @Test
    void merchantsGetTheirOwnSequenceAndTheSameKeyKeepsThePseudonym() {
        Pseudonyms pseudonyms = new Pseudonyms();

        String first = pseudonyms.assign(PseudonymKind.MERCHANT, "maria exemplo", "SEM_CATEGORIA", "Maria Exemplo");
        String second = pseudonyms.assign(PseudonymKind.MERCHANT, "ifood", "FOOD_OUT", "iFood");
        String again = pseudonyms.assign(PseudonymKind.MERCHANT, "maria exemplo", "SEM_CATEGORIA", "Maria Exemplo");

        assertThat(first).isEqualTo("comerciante_1");
        assertThat(second).isEqualTo("comerciante_2");
        assertThat(again).isEqualTo(first);
        assertThat(Pseudonyms.TOKEN.matcher("o comerciante_2 subiu").find()).isTrue();
        assertThat(Pseudonyms.withoutPseudonyms("comerciante_12")).doesNotContainPattern("\\d");
        assertThat(pseudonyms.toDisplay("comerciante_1 e comerciante_2")).isEqualTo("Maria Exemplo e iFood");
        assertThat(pseudonyms.find("comerciante_1").orElseThrow().toString()).doesNotContain("Maria");
    }

    @Test
    void citedIgnoresAccentsCaseAndPunctuationButNeedsTheWholeName() {
        Pseudonyms pseudonyms = new Pseudonyms("Quanto mandei pro JOÃO da Silva-ME e gastei no iFood?");
        String joao = pseudonyms.assign(PseudonymKind.MERCHANT, "joao da silva", "SEM_CATEGORIA", "Joao Da Silva");
        String ifood = pseudonyms.assign(PseudonymKind.MERCHANT, "ifood", "FOOD_OUT", "iFood");
        String maria = pseudonyms.assign(PseudonymKind.MERCHANT, "maria", "SEM_CATEGORIA", "Maria");
        String silva = pseudonyms.assign(PseudonymKind.MERCHANT, "silva mercado", "GROCERIES", "Silva Mercado");

        assertThat(pseudonyms.cited(joao)).isTrue();
        assertThat(pseudonyms.cited(ifood)).isTrue();
        assertThat(pseudonyms.cited(maria)).isFalse();
        assertThat(pseudonyms.cited(silva)).isFalse();
        assertThat(pseudonyms.cited("comerciante_9")).isFalse();
    }

    @Test
    void shortNamesAndMissingQuestionNeverCount() {
        Pseudonyms withQuestion = new Pseudonyms("Gastei muito no BK e na Zé?");
        String bk = withQuestion.assign(PseudonymKind.MERCHANT, "bk", "FOOD_OUT", "BK");
        String ze = withQuestion.assign(PseudonymKind.MERCHANT, "ze", "FOOD_OUT", "Zé");
        Pseudonyms withoutQuestion = new Pseudonyms();
        String ifood = withoutQuestion.assign(PseudonymKind.MERCHANT, "ifood", "FOOD_OUT", "iFood");

        assertThat(withQuestion.cited(bk)).isFalse();
        assertThat(withQuestion.cited(ze)).isFalse();
        assertThat(withoutQuestion.cited(ifood)).isFalse();
    }
}
