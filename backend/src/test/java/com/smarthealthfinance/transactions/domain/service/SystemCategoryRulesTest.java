package com.smarthealthfinance.transactions.domain.service;

import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Casos reais de design/specs/09-dados-de-exemplo.md e da fixture de E2E. */
class SystemCategoryRulesTest {

    @ParameterizedTest(name = "despesa \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Mercado Bom Preço|GROCERIES",
            "Padaria Pão Quente|GROCERIES",
            "Restaurante Sabor da Casa|DINING_OUT",
            "Café Grão|DINING_OUT",
            "Bistrô Lume|DINING_OUT",
            "Pizzaria Forno|DINING_OUT",
            "Entrega Já iFood|DINING_OUT",
            "Posto de Combustível Central|TRANSPORT",
            "Transporte por aplicativo|TRANSPORT",
            "Farmácia Saúde Já|HEALTH",
            "Academia Movimento|FITNESS",
            "Streaming de Vídeo Exemplo|SUBSCRIPTIONS",
            "Aluguel - Imobiliária Exemplo|HOUSING",
            "Condomínio Residencial Modelo|HOUSING",
            "Companhia de Energia Fictícia|HOUSING",
            "Internet Banda Larga Teste|HOUSING",
            "Seguro auto - parcela 3/12|INSURANCE",
            "IPTU 2026|TAXES",
            "Tarifa mensal da conta|BANK_FEES",
    })
    void categorizesExpensesByKeyword(String description, String code) {
        assertThat(SystemCategoryRules.match(CategoryNature.EXPENSE, description))
                .contains(new CategoryCode(code));
    }

    @ParameterizedTest(name = "receita \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Salário - Empresa Fictícia Ltda|SALARY",
            "Rendimento poupança|INVESTMENT_INCOME",
            "Pix recebido - Freelance Estúdio Ponto|SELF_EMPLOYED",
            "Aluguel recebido - Inquilino Exemplo|RENTAL_INCOME",
    })
    void categorizesIncomeByKeyword(String description, String code) {
        assertThat(SystemCategoryRules.match(CategoryNature.INCOME, description))
                .contains(new CategoryCode(code));
    }

    @Test
    void personsAndUnknownMerchantsStayUncategorized() {
        assertThat(SystemCategoryRules.match(CategoryNature.EXPENSE, "Pix enviado - Maria Exemplo")).isEmpty();
        assertThat(SystemCategoryRules.match(CategoryNature.EXPENSE, "Loja Exemplo")).isEmpty();
        assertThat(SystemCategoryRules.match(CategoryNature.INCOME, "Estorno compra online - Loja Exemplo"))
                .isEmpty();
    }

    @Test
    void natureDecidesWhichRulesApply() {
        // "Aluguel" é moradia para quem paga e aluguel recebido para quem recebe.
        assertThat(SystemCategoryRules.match(CategoryNature.EXPENSE, "Aluguel")).contains(new CategoryCode("HOUSING"));
        assertThat(SystemCategoryRules.match(CategoryNature.INCOME, "Aluguel"))
                .contains(new CategoryCode("RENTAL_INCOME"));
    }

    @Test
    void matchesWholeWordsOnly() {
        assertThat(SystemCategoryRules.match(CategoryNature.EXPENSE, "Barbearia Central")).isEmpty();
        assertThat(SystemCategoryRules.match(CategoryNature.EXPENSE, "Petiscaria Xyz")).isEmpty();
    }

    @Test
    void ignoresAccentsCaseAndPunctuation() {
        assertThat(SystemCategoryRules.match(CategoryNature.EXPENSE, "FARMÁCIA*SAÚDE"))
                .contains(new CategoryCode("HEALTH"));
    }
}
