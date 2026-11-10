package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader;
import com.smarthealthfinance.analytics.application.usecase.AnalysisQueries;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code CATEGORY} (ADR-0039 §4) sobre {@link AnalysisQueries#categories} e {@link AnalysisQueries#category}: até 8
 * categorias do mês (nome do catálogo como {@code categoria_N} mais o nome em texto, valor, participação e variação
 * contra o mês anterior) e, com uma categoria pedida, o detalhe dela (valor, transações, ticket médio, histórico e até
 * 5 comerciantes normalizados). Participação e variação percentual já vêm em pontos percentuais do caso de uso.
 */
@Component
public class CategoryTool implements FinancialTool {

    static final String SOURCE = AnalyticsSupport.SOURCE;
    static final int MAX_CATEGORIES = 8;
    static final int MAX_MERCHANTS = 5;
    static final String NO_CATEGORY_CODE = "SEM_CATEGORIA";
    static final String NO_CATEGORY_NAME = "Sem categoria";

    private final AnalysisQueries analysis;

    public CategoryTool(AnalysisQueries analysis) {
        this.analysis = analysis;
    }

    @Override
    public Intent intent() {
        return Intent.CATEGORY;
    }

    @Override
    public Result collect(Request request) {
        AnalysisContextLoader.Query query = AnalyticsSupport.query(request);
        AnalysisViews.Categories categories = analysis.categories(request.workspaceId(), query);
        Optional<Status> unavailable = AnalyticsSupport.unavailable(categories.meta());
        if (unavailable.isPresent()) {
            return Result.of(unavailable.get());
        }
        String period = AnalyticsSupport.period(categories.meta());
        ToolValues.Builder values = new ToolValues.Builder(SOURCE);
        List<OfferedAction> actions = new ArrayList<>();
        Integer monthsWithData = null;

        Optional<String> code = request.decision().categoryValue();
        if (code.isPresent()) {
            AnalysisViews.CategoryDetail detail = analysis.category(request.workspaceId(), code.get(), query);
            monthsWithData = detail(request, values, detail, period);
            actions.add(new OfferedAction(ActionKind.OPEN_ANALYSIS_CATEGORY,
                    ToolValues.category(request.pseudonyms(), code.get(), request.categoryName(code.get()))));
        }

        values.money("Despesa total do período", categories.totalExpense(), period);
        categories.categories().stream().limit(MAX_CATEGORIES).forEach(line -> {
            String pseudonym = pseudonym(request, line.categoryCode());
            values.text("Nome de " + pseudonym, name(request, line.categoryCode()), null)
                    .money("Despesa em " + pseudonym, line.amount(), period)
                    .percent("Participação de " + pseudonym + " na despesa total", line.share(), period);
            AnalyticsSupport.variation(values, "despesa em " + pseudonym, line.vsPrevious(), period);
        });

        DataQuality quality = new DataQuality(monthsWithData, categories.meta().period().partial(), List.of(),
                AnalyticsSupport.freshness(categories.meta()), Set.of());
        return new Result(Status.DATA, values.build(), quality,
                List.of(new SourceRef("AnalysisQueries", SOURCE)), actions);
    }

    /** @return meses do histórico com movimentação */
    private static int detail(Request request, ToolValues.Builder values, AnalysisViews.CategoryDetail detail,
                              String period) {
        String pseudonym = pseudonym(request, detail.categoryCode());
        values.text("Nome de " + pseudonym, name(request, detail.categoryCode()), null);
        if (detail.amount() != null) {
            values.money("Valor da categoria " + pseudonym + " no período", detail.amount().amount(), period);
            AnalyticsSupport.variation(values, "valor da categoria " + pseudonym, detail.amount().vsPrevious(),
                    period);
        }
        values.count("Transações da categoria " + pseudonym + " no período", detail.transactionCount(), period)
                .money("Ticket médio da categoria " + pseudonym, detail.averageTicket(), period);
        int monthsWithData = 0;
        for (AnalysisViews.CategoryMonth month : detail.history()) {
            values.money("Valor mensal da categoria " + pseudonym + " no histórico", month.amount(),
                    ToolValues.month(month.month()));
            if (month.transactionCount() > 0 || AnalyticsSupport.anyMovement(month.amount())) {
                monthsWithData++;
            }
        }
        List<AnalysisViews.MerchantAmount> merchants = detail.merchants().stream().limit(MAX_MERCHANTS).toList();
        for (int i = 0; i < merchants.size(); i++) {
            AnalysisViews.MerchantAmount merchant = merchants.get(i);
            String ordinal = ToolSupport.ordinal(i);
            values.text("Comerciante " + ordinal + " da categoria " + pseudonym, merchant.merchantName(), period)
                    .money("Valor no comerciante " + ordinal + " da categoria " + pseudonym, merchant.amount(),
                            period)
                    .count("Transações no comerciante " + ordinal + " da categoria " + pseudonym,
                            merchant.transactionCount(), period);
        }
        return monthsWithData;
    }

    private static String pseudonym(Request request, String code) {
        return code == null ? ToolValues.category(request.pseudonyms(), NO_CATEGORY_CODE, NO_CATEGORY_NAME)
                : ToolValues.category(request.pseudonyms(), code, request.categoryName(code));
    }

    private static String name(Request request, String code) {
        return code == null ? NO_CATEGORY_NAME : request.categoryName(code);
    }
}
