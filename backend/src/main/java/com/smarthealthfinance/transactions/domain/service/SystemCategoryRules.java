package com.smarthealthfinance.transactions.domain.service;

import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Regras do sistema: palavras-chave → categoria do catálogo (ADR-0013 §2). Determinístico e versionado: qualquer
 * mudança na lista exige nova {@link #VERSION}. A primeira regra que casa vence; a ordem é a prioridade.
 * <p>
 * As palavras são procuradas na descrição inteira (dobrada, sem pontuação), por palavra ou frase inteira.
 * Quem não casa fica sem categoria: não adivinhar vale mais que errar.
 */
public final class SystemCategoryRules {

    public static final String VERSION = "system-v1";

    private record Rule(CategoryNature nature, String code, List<String> keywords) {
    }

    private static final List<Rule> RULES = List.of(
            // Receitas
            income("SALARY", "salario", "folha de pagamento", "remuneracao"),
            income("RENTAL_INCOME", "aluguel recebido", "aluguel"),
            income("INVESTMENT_INCOME", "rendimento", "rendimentos", "dividendo", "dividendos", "juros",
                    "proventos"),
            income("BENEFITS", "beneficio", "inss", "auxilio", "bolsa familia"),
            income("SELF_EMPLOYED", "freelance", "freela", "autonomo", "honorarios"),

            // Despesas: fixas e contas da casa
            expense("HOUSING", "aluguel", "condominio", "energia", "agua e esgoto", "saneamento", "gas encanado",
                    "internet", "banda larga"),
            expense("INSURANCE", "seguro", "seguros"),
            expense("TAXES", "imposto", "impostos", "iptu", "ipva", "darf", "das mei", "taxa municipal"),
            expense("BANK_FEES", "tarifa", "tarifas", "anuidade", "iof"),

            // Despesas: dia a dia
            expense("GROCERIES", "mercado", "supermercado", "hortifruti", "atacado", "padaria", "acougue",
                    "feira"),
            expense("DINING_OUT", "restaurante", "lanchonete", "pizzaria", "hamburgueria", "cafe", "cafeteria",
                    "bistro", "ifood", "delivery", "sorveteria", "bar"),
            expense("TRANSPORT", "posto", "combustivel", "gasolina", "uber", "taxi", "transporte por aplicativo",
                    "estacionamento", "pedagio", "metro", "onibus", "bilhete unico"),
            expense("HEALTH", "farmacia", "drogaria", "clinica", "hospital", "laboratorio", "dentista", "consulta",
                    "plano de saude"),
            expense("FITNESS", "academia", "pilates", "crossfit", "natacao"),
            expense("SUBSCRIPTIONS", "streaming", "assinatura", "netflix", "spotify", "disney"),
            expense("EDUCATION", "escola", "faculdade", "curso", "universidade", "livraria"),
            expense("PETS", "pet", "petshop", "veterinario", "veterinaria"),
            expense("LEISURE", "cinema", "teatro", "ingresso", "show", "parque"),
            expense("CLOTHING", "roupa", "roupas", "vestuario", "moda", "calcados"),
            expense("TRAVEL", "hotel", "pousada", "passagem", "aereo", "hospedagem"));

    private SystemCategoryRules() {
    }

    private static Rule income(String code, String... keywords) {
        return new Rule(CategoryNature.INCOME, code, List.of(keywords));
    }

    private static Rule expense(String code, String... keywords) {
        return new Rule(CategoryNature.EXPENSE, code, List.of(keywords));
    }

    /** @return o código da primeira regra da {@code nature} que casa com a descrição */
    public static Optional<CategoryCode> match(CategoryNature nature, String description) {
        if (description == null) {
            return Optional.empty();
        }
        String haystack = " " + Pattern.compile("[^a-z0-9]+")
                .matcher(MerchantNormalizer.fold(description)).replaceAll(" ").strip() + " ";

        return RULES.stream()
                .filter(rule -> rule.nature() == nature)
                .filter(rule -> rule.keywords().stream().anyMatch(keyword -> haystack.contains(" " + keyword + " ")))
                .findFirst()
                .map(rule -> new CategoryCode(rule.code()));
    }
}
