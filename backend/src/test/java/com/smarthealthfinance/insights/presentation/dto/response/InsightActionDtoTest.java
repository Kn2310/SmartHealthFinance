package com.smarthealthfinance.insights.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import org.junit.jupiter.api.Test;

/** "Perguntar ao Copilot" sai disponível também nos insights gravados antes do M11 (ADR-0041 §6). */
class InsightActionDtoTest {

    @Test
    void askCopilotStoredAsComingSoonIsAvailable() {
        InsightResponses.InsightActionDto dto = InsightResponses.InsightActionDto.of(
                InsightAction.comingSoon(ActionKind.ASK_COPILOT, "Perguntar ao Copilot"));

        assertThat(dto.available()).isTrue();
    }

    @Test
    void otherComingSoonActionsStayUnavailable() {
        InsightResponses.InsightActionDto dto = InsightResponses.InsightActionDto.of(
                InsightAction.comingSoon(ActionKind.VIEW_CARD, "Ver cartão"));

        assertThat(dto.available()).isFalse();
    }
}
