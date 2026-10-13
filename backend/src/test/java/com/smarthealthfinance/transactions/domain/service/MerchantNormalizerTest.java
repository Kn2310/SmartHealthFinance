package com.smarthealthfinance.transactions.domain.service;

import com.smarthealthfinance.transactions.domain.valueobject.Merchant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Casos reais de design/specs/09-dados-de-exemplo.md e da fixture de E2E (extrato-ficticio-2026-jul-set.csv). */
class MerchantNormalizerTest {

    @ParameterizedTest(name = "\"{0}\" -> {1} / {2}")
    @CsvSource(delimiter = '|', value = {
            "Mercado Bom Preço|Mercado Bom Preço|mercado bom preco",
            "Salário - Empresa Fictícia Ltda|Empresa Fictícia|empresa ficticia",
            "Aluguel - Imobiliária Exemplo|Imobiliária Exemplo|imobiliaria exemplo",
            "Pix enviado - Maria Exemplo|Maria Exemplo|maria exemplo",
            "Pix recebido - Freelance Estúdio Ponto|Freelance Estúdio Ponto|freelance estudio ponto",
            "Estorno compra online - Loja Exemplo|Loja Exemplo|loja exemplo",
            "Seguro auto - parcela 3/12|Seguro auto|seguro auto",
            "Companhia de Energia Fictícia|Companhia de Energia Fictícia|companhia de energia ficticia",
            "Entrega Já|Entrega Já|entrega ja",
            "BISTRO LUME PIRACICABA|Bistro Lume Piracicaba|bistro lume piracicaba",
            "Compra no débito - Café Grão 22/09|Café Grão|cafe grao",
            "Padaria Pão Quente 0712|Padaria Pão Quente|padaria pao quente",
            "Supermercado Sol S/A|Supermercado Sol|supermercado sol",
            "Posto Central ME|Posto Central|posto central",
    })
    void extractsTheMerchantFromTheRawDescription(String description, String name, String key) {
        assertThat(MerchantNormalizer.normalize(description))
                .contains(new Merchant(key, name, MerchantNormalizer.VERSION));
    }

    @Test
    void sameMerchantWithDifferentNoiseSharesTheKey() {
        String a = MerchantNormalizer.normalize("MERCADO BOM PREÇO 0712").orElseThrow().key();
        String b = MerchantNormalizer.normalize("Mercado Bom Preço - 15/08").orElseThrow().key();
        String c = MerchantNormalizer.normalize("  mercado   bom preco ").orElseThrow().key();

        assertThat(a).isEqualTo(b).isEqualTo(c).isEqualTo("mercado bom preco");
    }

    @Test
    void genericLabelAloneIsTheDescriptionItself() {
        assertThat(MerchantNormalizer.normalize("Aluguel")).map(Merchant::key).contains("aluguel");
        assertThat(MerchantNormalizer.normalize("Pix")).map(Merchant::key).contains("pix");
    }

    @Test
    void labelsOnlyMatchWholeWords() {
        assertThat(MerchantNormalizer.normalize("Pixel Store")).map(Merchant::key).contains("pixel store");
        assertThat(MerchantNormalizer.normalize("Doces Mel")).map(Merchant::key).contains("doces mel");
    }

    @Test
    void nothingMeaningfulMeansNoMerchant() {
        assertThat(MerchantNormalizer.normalize("123456")).isEmpty();
        assertThat(MerchantNormalizer.normalize("  --  ")).isEmpty();
        assertThat(MerchantNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void isDeterministicAndCarriesTheAlgorithmVersion() {
        Merchant first = MerchantNormalizer.normalize("Café Grão 22/09").orElseThrow();

        assertThat(MerchantNormalizer.normalize("Café Grão 22/09")).contains(first);
        assertThat(first.normalizerVersion()).isEqualTo("v1");
    }

    @Test
    void longDescriptionsAreTruncatedToTheMerchantLimit() {
        Merchant merchant = MerchantNormalizer.normalize("Loja " + "x".repeat(300)).orElseThrow();

        assertThat(merchant.name().length()).isLessThanOrEqualTo(Merchant.MAX_LENGTH);
        assertThat(merchant.key().length()).isLessThanOrEqualTo(Merchant.MAX_LENGTH);
    }
}
