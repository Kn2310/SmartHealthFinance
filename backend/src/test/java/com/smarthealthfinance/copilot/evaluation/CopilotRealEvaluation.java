package com.smarthealthfinance.copilot.evaluation;

import com.smarthealthfinance.copilot.application.port.AiGateway;
import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.application.port.AiResult;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.infrastructure.config.RealCopilotWiring;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Avaliação do Copilot com o modelo real (ADR-0042 §3): roda o mesmo golden set do CI com o adapter Anthropic, ignora
 * a saída roteirizada e confere intenção, código, validação (modo seguro conta como falha de qualidade), que todo
 * número do texto vem de um valor do contexto e as recusas esperadas. Mede latência por papel, tokens e custo pela
 * tabela de preços e escreve {@code target/copilot-eval/report.json} e {@code report.md} sem texto de pergunta,
 * resposta ou contexto.
 *
 * <p><b>Não roda no {@code verify}:</b> o nome não casa com os padrões do surefire nem do failsafe; só o perfil Maven
 * {@code copilot-eval} o inclui. Lê {@code ANTHROPIC_API_KEY} só do ambiente e, sem ela, falha com uma mensagem clara,
 * sem cair no simulado. Opções: {@code -Dcopilot.eval.cases=id1,group:adversarial,intent:CARDS} (subconjunto) e
 * {@code -Dcopilot.eval.maxUsd=2.00} (teto de custo, interrompe a execução). A execução falha por: chave ausente,
 * modelo sem preço ou teto de custo atingido; qualidade abaixo do esperado é lida no relatório, não derruba o perfil.
 */
class CopilotRealEvaluation {

    static final String CASES = "copilot.eval.cases";
    static final String MAX_USD = "copilot.eval.maxUsd";
    static final String DEFAULT_MAX_USD = "2.00";
    static final Path REPORT_DIR = Path.of("target", "copilot-eval");

    @Test
    void evaluateTheGoldenSetAgainstTheRealModel() throws IOException {
        RealCopilotWiring.Wiring wiring = RealCopilotWiring.anthropic();
        BigDecimal maxUsd = parseMaxUsd(System.getProperty(MAX_USD, DEFAULT_MAX_USD));
        List<GoldenCase> all = GoldenSet.cases();
        List<GoldenCase> selected = select(all, System.getProperty(CASES));
        List<GoldenCase> toRun = selected.stream().filter(golden -> !GoldenChecks.skipped(golden)).toList();
        int skipped = selected.size() - toRun.size();
        System.out.printf("copilot-eval: %d case(s) selected of %d, %d skipped (scripted-only), ceiling US$ %s%n",
                selected.size(), all.size(), skipped, maxUsd.toPlainString());

        List<CopilotEvaluationReport.Run> runs = new ArrayList<>();
        BigDecimal spent = BigDecimal.ZERO;
        String stop = null;
        String abortReason = null;
        try {
            for (int i = 0; i < toRun.size(); i++) {
                if (spent.compareTo(maxUsd) >= 0) {
                    abortReason = "COST_CEILING";
                    stop = "cost ceiling US$ " + maxUsd.toPlainString() + " reached after " + runs.size() + " of "
                            + toRun.size() + " cases";
                    break;
                }
                GoldenCase golden = toRun.get(i);
                ElapsedClock clock = new ElapsedClock();
                AiGateway gateway = new Rebasing(wiring.gateways().apply(new SimpleMeterRegistry()), clock);
                GoldenHarness.Outcome outcome = GoldenHarness.run(golden, gateway, clock, wiring.pricing());
                List<GoldenChecks.Failure> failures = GoldenChecks.checkReal(golden, outcome);
                CopilotEvaluationReport.Run run = new CopilotEvaluationReport.Run(golden, outcome, failures);
                runs.add(run);
                spent = spent.add(run.cost());
                System.out.printf("[%d/%d] %s %s%s cost=US$ %s%n", i + 1, toRun.size(), golden.id(),
                        run.passed() ? "PASS" : "FAIL", run.passed() ? "" : " " + failures.stream()
                                .map(GoldenChecks.Failure::check).distinct().collect(Collectors.joining(",")),
                        spent.toPlainString());
                Set<String> unpriced = outcome.ledger().stream().map(AiUsage::model)
                        .filter(model -> wiring.pricing().find(model).isEmpty()).collect(Collectors.toSet());
                if (!unpriced.isEmpty()) {
                    abortReason = "UNPRICED_MODEL";
                    stop = "model(s) without a price in the pricing table: " + unpriced;
                    break;
                }
            }
        } catch (RuntimeException | Error failure) {
            // Só o tipo da exceção vai para o relatório: a mensagem pode carregar texto da resposta do provedor.
            abortReason = "ERROR_" + failure.getClass().getSimpleName();
            throw failure;
        } finally {
            writeReport(wiring, maxUsd, abortReason, skipped, all.size() - selected.size(), runs);
        }
        if (stop != null) {
            throw new IllegalStateException("copilot-eval stopped: " + stop + ". Partial report in "
                    + REPORT_DIR.resolve("report.json"));
        }
    }

    private static void writeReport(RealCopilotWiring.Wiring wiring, BigDecimal maxUsd, String abortReason,
                                    int skipped, int notSelected, List<CopilotEvaluationReport.Run> runs)
            throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        ObjectNode report = CopilotEvaluationReport.json(mapper, new CopilotEvaluationReport.Meta(GoldenSet.VERSION,
                wiring.pricing().version(), wiring.intentModel(), wiring.answerModel(), maxUsd, abortReason,
                skipped, notSelected), runs);
        Files.createDirectories(REPORT_DIR);
        Files.writeString(REPORT_DIR.resolve("report.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        Files.writeString(REPORT_DIR.resolve("report.md"), CopilotEvaluationReport.markdown(report));
        System.out.println("copilot-eval: report written to " + REPORT_DIR.resolve("report.md") + " and report.json");
    }

    /** Teto de custo em US$: decimal positivo ({@code 2.00}); qualquer outra coisa falha antes de gastar um token. */
    static BigDecimal parseMaxUsd(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw.strip());
            if (value.signum() > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // cai na mensagem abaixo
        }
        throw new IllegalArgumentException("-D" + MAX_USD + " must be a positive decimal number such as 2.00");
    }

    /**
     * Seleção por {@code -Dcopilot.eval.cases}: lista separada por vírgula de ids, {@code group:<grupo>} ou
     * {@code intent:<INTENÇÃO>} (a esperada). Em branco: todos. Um item sem nenhum caso é erro.
     */
    static List<GoldenCase> select(List<GoldenCase> all, String spec) {
        if (spec == null || spec.isBlank()) {
            return all;
        }
        List<GoldenCase> selected = new ArrayList<>();
        for (String raw : spec.split(",")) {
            String item = raw.strip();
            if (item.isEmpty()) {
                continue;
            }
            List<GoldenCase> matches = all.stream().filter(golden -> matches(golden, item)).toList();
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("-D" + CASES + ": no golden case matches '" + item
                        + "' (use an id, group:<group> or intent:<INTENT>)");
            }
            matches.stream().filter(golden -> !selected.contains(golden)).forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    private static boolean matches(GoldenCase golden, String item) {
        if (item.startsWith("group:")) {
            return golden.group().equals(item.substring("group:".length()));
        }
        if (item.startsWith("intent:")) {
            return item.substring("intent:".length()).equals(golden.expect().intent());
        }
        return golden.id().equals(item);
    }

    /** Data de referência dos dados de exemplo mais o tempo real decorrido, para o orçamento do pipeline valer. */
    static final class ElapsedClock extends Clock {

        private final long origin = System.nanoTime();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return GoldenHarness.NOW.plusNanos(System.nanoTime() - origin);
        }
    }

    /**
     * O pipeline calcula o prazo no relógio dos dados de exemplo (22/09/2026); o provedor real mede o prazo no relógio
     * de verdade. Reescreve o prazo preservando o tempo que resta.
     */
    private record Rebasing(AiGateway delegate, Clock pipelineClock) implements AiGateway {

        @Override
        public AiResult classify(AiRequest request) {
            return delegate.classify(rebase(request));
        }

        @Override
        public AiResult answer(AiRequest request) {
            return delegate.answer(rebase(request));
        }

        private AiRequest rebase(AiRequest request) {
            if (request.deadline() == null) {
                return request;
            }
            Duration remaining = Duration.between(pipelineClock.instant(), request.deadline());
            return new AiRequest(request.systemPrompt(), request.userContent(), request.jsonSchema(),
                    Instant.now().plus(remaining));
        }
    }
}
