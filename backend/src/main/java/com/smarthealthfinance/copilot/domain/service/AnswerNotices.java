package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Notice;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;

import java.util.ArrayList;
import java.util.List;

/** Avisos da resposta derivados do contexto calculado e da validação (ADR-0040 §3), em ordem fixa. */
public final class AnswerNotices {

    private AnswerNotices() {
    }

    public static List<Notice> of(CopilotContext context, ValidationStatus validation) {
        List<Notice> notices = new ArrayList<>();
        if (context.quality().partialPeriod()) {
            notices.add(Notice.PARTIAL_PERIOD);
        }
        if (context.has(ValueKind.ESTIMATED)) {
            notices.add(Notice.ESTIMATED);
        }
        if (context.has(ValueKind.SIMULATED)) {
            notices.add(Notice.SIMULATED);
        }
        if (validation == ValidationStatus.SAFE_MODE) {
            notices.add(Notice.SAFE_MODE);
        }
        if (context.quality().freshness() == DataFreshness.UPDATING) {
            notices.add(Notice.UPDATING);
        }
        return List.copyOf(notices);
    }
}
