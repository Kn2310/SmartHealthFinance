package com.smarthealthfinance.copilot.infrastructure.persistence.adapter;

import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.Notice;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.AnswerSegment;
import com.smarthealthfinance.copilot.domain.model.Breakdown;
import com.smarthealthfinance.copilot.domain.model.DataUsedItem;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON da coluna {@code answer} do histórico (ADR-0040 §6, §10): a resposta renderizada (segmentos, valores já
 * formatados, quadro, ações, "dados usados") e os avisos, para exibir. Nunca o contexto financeiro, as refs cruas nem o
 * mapa de pseudônimos: o {@link RenderedAnswer} já não os tem. Lê e escreve o mesmo formato.
 */
@Component
class StoredAnswerCodec {

    private final JsonMapper json;

    StoredAnswerCodec(JsonMapper json) {
        this.json = json;
    }

    String write(RenderedAnswer answer, List<Notice> notices) {
        ObjectNode root = json.createObjectNode();
        ArrayNode noticeNodes = root.putArray("notices");
        notices.forEach(notice -> noticeNodes.add(notice.name()));
        ObjectNode body = root.putObject("body");
        body.put("type", answer.type().name());
        body.put("text", answer.text());
        body.set("segments", segments(answer.segments()));
        ArrayNode explanation = body.putArray("explanation");
        answer.explanation().forEach(explanation::add);
        ArrayNode explanationSegments = body.putArray("explanationSegments");
        answer.explanationSegments().forEach(step -> explanationSegments.add(segments(step)));
        ArrayNode values = body.putArray("values");
        for (RenderedAnswer.Value value : answer.financialData()) {
            ObjectNode node = values.addObject();
            node.put("ref", value.ref());
            node.put("label", value.label());
            node.put("formatted", value.formatted());
            node.put("kind", value.kind().name());
            node.put("period", value.period());
            node.put("source", value.source());
        }
        ArrayNode actions = body.putArray("actions");
        for (RenderedAnswer.Action action : answer.actions()) {
            ObjectNode node = actions.addObject();
            node.put("kind", action.kind().name());
            node.put("targetId", action.targetId());
            node.put("targetName", action.targetName());
            ObjectNode params = node.putObject("params");
            action.params().forEach(params::put);
        }
        if (answer.breakdown() == null) {
            body.putNull("breakdown");
        } else {
            ObjectNode node = body.putObject("breakdown");
            node.put("title", answer.breakdown().title());
            node.put("totalRef", answer.breakdown().totalRef());
            ArrayNode rows = node.putArray("rows");
            for (Breakdown.Row row : answer.breakdown().rows()) {
                ObjectNode rowNode = rows.addObject();
                rowNode.put("label", row.label());
                rowNode.put("valueRef", row.valueRef());
                rowNode.put("detail", row.detail());
            }
        }
        ArrayNode dataUsed = body.putArray("dataUsed");
        for (DataUsedItem item : answer.dataUsed()) {
            ObjectNode node = dataUsed.addObject();
            node.put("label", item.label());
            node.put("partial", item.partial());
        }
        return json.writeValueAsString(root);
    }

    /** O que a coluna guarda: a resposta e os avisos. */
    record Stored(RenderedAnswer answer, List<Notice> notices) {
    }

    Stored read(String stored) {
        JsonNode root = json.readTree(stored);
        List<Notice> notices = new ArrayList<>();
        root.required("notices").forEach(node -> notices.add(Notice.valueOf(node.asString())));
        JsonNode body = root.required("body");
        List<String> explanation = new ArrayList<>();
        body.required("explanation").forEach(node -> explanation.add(node.asString()));
        List<List<AnswerSegment>> explanationSegments = new ArrayList<>();
        body.required("explanationSegments").forEach(step -> explanationSegments.add(segments(step)));
        List<RenderedAnswer.Value> values = new ArrayList<>();
        body.required("values").forEach(node -> values.add(new RenderedAnswer.Value(text(node, "ref"),
                text(node, "label"), text(node, "formatted"), ValueKind.valueOf(text(node, "kind")),
                text(node, "period"), text(node, "source"))));
        List<RenderedAnswer.Action> actions = new ArrayList<>();
        body.required("actions").forEach(node -> {
            Map<String, String> params = new LinkedHashMap<>();
            node.required("params").properties().forEach(entry -> params.put(entry.getKey(),
                    entry.getValue().asString()));
            actions.add(new RenderedAnswer.Action(ActionKind.valueOf(text(node, "kind")), text(node, "targetId"),
                    text(node, "targetName"), params));
        });
        Breakdown breakdown = null;
        JsonNode breakdownNode = body.get("breakdown");
        if (breakdownNode != null && !breakdownNode.isNull()) {
            List<Breakdown.Row> rows = new ArrayList<>();
            breakdownNode.required("rows").forEach(row -> rows.add(new Breakdown.Row(text(row, "label"),
                    text(row, "valueRef"), text(row, "detail"))));
            breakdown = new Breakdown(text(breakdownNode, "title"), rows, text(breakdownNode, "totalRef"));
        }
        List<DataUsedItem> dataUsed = new ArrayList<>();
        body.required("dataUsed").forEach(node -> dataUsed.add(new DataUsedItem(text(node, "label"),
                node.required("partial").asBoolean())));
        RenderedAnswer answer = new RenderedAnswer(AnswerType.valueOf(text(body, "type")), text(body, "text"),
                segments(body.required("segments")), explanation, explanationSegments, values, actions, breakdown,
                dataUsed);
        return new Stored(answer, notices);
    }

    private ArrayNode segments(List<AnswerSegment> segments) {
        ArrayNode array = json.createArrayNode();
        for (AnswerSegment segment : segments) {
            ObjectNode node = array.addObject();
            switch (segment) {
                case AnswerSegment.Text text -> {
                    node.put("kind", "TEXT");
                    node.put("text", text.text());
                }
                case AnswerSegment.Value value -> {
                    node.put("kind", "VALUE");
                    node.put("ref", value.ref());
                }
            }
        }
        return array;
    }

    private static List<AnswerSegment> segments(JsonNode array) {
        List<AnswerSegment> segments = new ArrayList<>();
        array.forEach(node -> segments.add("TEXT".equals(text(node, "kind"))
                ? new AnswerSegment.Text(text(node, "text"))
                : new AnswerSegment.Value(text(node, "ref"))));
        return segments;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
