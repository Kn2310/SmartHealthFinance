package com.smarthealthfinance.copilot.infrastructure.persistence.adapter;

import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.Notice;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.AnswerSegment;
import com.smarthealthfinance.copilot.domain.model.Breakdown;
import com.smarthealthfinance.copilot.domain.model.DataUsedItem;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A resposta guardada no histórico volta idêntica: segmentos, valores, quadro, ações e "dados usados". */
class StoredAnswerCodecTest {

    private final StoredAnswerCodec codec = new StoredAnswerCodec(JsonMapper.builder().build());

    @Test
    void aFullAnswerRoundTrips() {
        List<AnswerSegment> intro = List.of(new AnswerSegment.Text("Sua fatura está em "),
                new AnswerSegment.Value("v1"));
        RenderedAnswer answer = new RenderedAnswer(AnswerType.EXPLANATION, "Sua fatura está em R$ 3.412,80",
                intro, List.of("Passo um"), List.of(List.of(new AnswerSegment.Text("Passo um"))),
                List.of(new RenderedAnswer.Value("v1", "Fatura", "R$ 3.412,80", ValueKind.FACT, "2026-10",
                        "cards-v1"),
                        new RenderedAnswer.Value("v2", "Projeção", "R$ 10,00", ValueKind.ESTIMATED, null, "forecast-v1")),
                List.of(new RenderedAnswer.Action(ActionKind.OPEN_INVOICE, "7d1c0a52-0000-4000-8000-000000000001",
                        "Aurora Platinum", Map.of("month", "2026-10")),
                        new RenderedAnswer.Action(ActionKind.OPEN_HEALTH, null, null, Map.of())),
                new Breakdown("Fatores", List.of(new Breakdown.Row("Mercado", "v2", "3 compras"),
                        new Breakdown.Row("Outros", "v1", null)), "v1"),
                List.of(new DataUsedItem("Fatura out/2026", true)));
        List<Notice> notices = List.of(Notice.PARTIAL_PERIOD, Notice.ESTIMATED);

        StoredAnswerCodec.Stored read = codec.read(codec.write(answer, notices));

        assertThat(read.answer()).isEqualTo(answer);
        assertThat(read.notices()).isEqualTo(notices);
    }

    @Test
    void aPlainAnswerWithoutBreakdownRoundTrips() {
        RenderedAnswer answer = RenderedAnswer.plain(AnswerType.OUT_OF_SCOPE, "Não posso ajudar com isso.", List.of());

        StoredAnswerCodec.Stored read = codec.read(codec.write(answer, List.of()));

        assertThat(read.answer()).isEqualTo(answer);
        assertThat(read.answer().breakdown()).isNull();
        assertThat(read.notices()).isEmpty();
    }
}
