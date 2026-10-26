package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.exception.CardNotFoundException;
import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.application.service.CardEvents;
import com.smarthealthfinance.cards.application.service.InvoiceAssignments;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.repository.InstallmentConfirmationRepository;
import com.smarthealthfinance.cards.domain.service.InstallmentSplitter;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.service.StatementInstallments;
import com.smarthealthfinance.cards.domain.service.StatementInstallments.Candidate;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.cards.domain.valueobject.InstallmentKey;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.usecase.RecordImportedTransactions;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Linhas de uma fatura importada (ADR-0009 §20–§28), chamado pelo processamento do Ingestion na mesma transação de
 * banco tudo-ou-nada do batch:
 * <ul>
 *     <li>crédito → {@code REFUND} no cartão, sem vínculo;</li>
 *     <li>compra comum → {@code EXPENSE} à vista;</li>
 *     <li>parcela k/n → conciliada com um parcelamento compatível ({@code RECONCILED}, nada é lançado) ou reconstruída
 *     como compra em n parcelas de valor igual à da linha, na data da compra;</li>
 *     <li>o início do acompanhamento recua até a fatura mais antiga com evidência no arquivo (ADR-0025 §15);</li>
 *     <li>vencimento e mínimo informados no arquivo vão para a fatura que vence naquele mês.</li>
 * </ul>
 * Sem autorização de usuário: roda no worker, e o batch foi autorizado na confirmação. O cartão é verificado de novo
 * (pode ter sido arquivado depois do preview).
 */
@Service
public class RecordImportedCardTransactions {

    private static final Logger log = LoggerFactory.getLogger(RecordImportedCardTransactions.class);

    private final CreditCardRepository cards;
    private final CardInvoiceRepository invoices;
    private final CardLedgerReader reader;
    private final InstallmentConfirmationRepository confirmations;
    private final InvoiceAssignments assignments;
    private final RecordImportedTransactions recordTransactions;
    private final CardEvents events;
    private final Clock clock;

    public RecordImportedCardTransactions(CreditCardRepository cards, CardInvoiceRepository invoices,
                                          CardLedgerReader reader, InstallmentConfirmationRepository confirmations,
                                          InvoiceAssignments assignments,
                                          RecordImportedTransactions recordTransactions, CardEvents events,
                                          Clock clock) {
        this.cards = cards;
        this.invoices = invoices;
        this.reader = reader;
        this.confirmations = confirmations;
        this.assignments = assignments;
        this.recordTransactions = recordTransactions;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Uma linha válida, já classificada pelo Ingestion (o pagamento nunca chega aqui).
     *
     * @param transactionId     id atribuído pelo chamador, já vinculado à chave de dedupe; usado se a linha virar
     *                          transação
     * @param credit            true para crédito
     * @param amount            valor positivo da linha
     * @param description       sem o marcador de parcela
     * @param installmentNumber parcela k, ou nulo
     * @param installmentCount  total de parcelas n, ou nulo
     */
    public record Line(UUID transactionId, boolean credit, Money amount, LocalDate occurredOn, String description,
                       Integer installmentNumber, Integer installmentCount) {

        public Line {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(occurredOn, "occurredOn");
            if ((installmentNumber == null) != (installmentCount == null)) {
                throw new IllegalArgumentException("Parcela exige número e total");
            }
        }

        boolean isInstallment() {
            return !credit && installmentCount != null;
        }
    }

    /**
     * Dados da fatura informados no arquivo (ADR-0009 §21); cada um pode ser nulo.
     *
     * @param fileLastDate maior data entre todas as linhas do arquivo: define a fatura do arquivo (§25)
     */
    public record Statement(LocalDate fileLastDate, LocalDate dueDate, Money minimumPayment) {

        public Statement {
            Objects.requireNonNull(fileLastDate, "fileLastDate");
        }
    }

    public enum Outcome {
        /** A linha virou transação (compra, compra reconstruída ou crédito). */
        IMPORTED,
        /** Parcela de uma compra já lançada: nada novo. */
        RECONCILED
    }

    /** @param transactionId a transação criada (IMPORTED) ou a compra conciliada (RECONCILED) */
    public record LineResult(UUID transactionId, Outcome outcome) {
    }

    /**
     * @param lines         um resultado por linha, na ordem recebida
     * @param categorized   das transações criadas, as que as regras categorizaram
     * @param earliestDate  data mais antiga das transações criadas (a compra reconstruída pode ser anterior ao
     *                      arquivo), ou vazio
     */
    public record Result(List<LineResult> lines, int categorized, Optional<LocalDate> earliestDate) {
    }

    /**
     * O cartão aceita uma importação: existe no Workspace e está ativo. Usado no upload, na confirmação e antes de
     * reservar as chaves no processamento (o Workspace já foi autorizado por quem chama).
     *
     * @throws CardNotFoundException cartão fora do Workspace
     * @throws com.smarthealthfinance.cards.domain.exception.CreditCardArchivedException cartão arquivado
     */
    public void requireImportable(WorkspaceId workspace, UUID cardId) {
        requireActive(workspace, cardId);
    }

    /**
     * @throws CardNotFoundException cartão fora do Workspace
     * @throws com.smarthealthfinance.cards.domain.exception.CreditCardArchivedException cartão arquivado
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result execute(WorkspaceId workspace, UUID cardId, Statement statement, List<Line> lines) {
        CreditCard card = requireActive(workspace, cardId);
        Instant now = clock.instant();

        List<CardInvoice> rows = invoices.findByCard(workspace, card.id());
        InvoiceCalendar calendar = new InvoiceCalendar(card.cycle(), rows.stream().map(CardInvoice::dates).toList());
        YearMonth fileMonth = calendar.closingMonthOf(statement.fileLastDate());

        List<Candidate> candidates = candidates(workspace, card, rows);
        Set<InstallmentKey> confirmed = new HashSet<>(confirmations.findByCard(workspace, card.id()));
        List<InstallmentKey> newConfirmations = new ArrayList<>();

        List<LineResult> results = new ArrayList<>(lines.size());
        List<RecordImportedTransactions.CardLine> created = new ArrayList<>();
        List<InvoiceAssignments.Purchase> purchases = new ArrayList<>();
        YearMonth earliestEvidence = null;

        for (Line line : lines) {
            if (!line.isInstallment()) {
                created.add(new RecordImportedTransactions.CardLine(new TransactionId(line.transactionId()),
                        line.credit(), line.amount(), line.occurredOn(), line.description()));
                if (!line.credit()) {
                    purchases.add(new InvoiceAssignments.Purchase(line.transactionId(), line.occurredOn(),
                            List.of(line.amount())));
                }
                results.add(new LineResult(line.transactionId(), Outcome.IMPORTED));
                earliestEvidence = earliest(earliestEvidence, calendar.closingMonthOf(line.occurredOn()));
                continue;
            }

            int number = line.installmentNumber();
            int count = line.installmentCount();
            LocalDate purchasedOn = StatementInstallments.purchaseDate(calendar, line.occurredOn(), number, fileMonth);
            YearMonth month = calendar.installmentClosingMonth(purchasedOn, number);
            earliestEvidence = earliest(earliestEvidence, month);

            Optional<UUID> match = StatementInstallments.match(candidates, confirmed, number, count, month,
                    line.amount());
            UUID purchase = match.orElse(line.transactionId());
            if (match.isPresent()) {
                results.add(new LineResult(purchase, Outcome.RECONCILED));
            }
            else {
                // Total = parcela × n, todas iguais (§26); a compra é contada uma vez, pelo total, na data da compra.
                Money total = new Money(line.amount().amount().multiply(BigDecimal.valueOf(count)),
                        line.amount().currency());
                List<Money> parts = InstallmentSplitter.split(total, count);
                created.add(new RecordImportedTransactions.CardLine(new TransactionId(purchase), false, total,
                        purchasedOn, line.description()));
                purchases.add(new InvoiceAssignments.Purchase(purchase, purchasedOn, parts));
                // A compra nova também concilia as próximas linhas do mesmo arquivo.
                for (int k = 1; k <= count; k++) {
                    candidates.add(new Candidate(purchase, purchasedOn, k, count,
                            calendar.installmentClosingMonth(purchasedOn, k), parts.get(k - 1)));
                }
                results.add(new LineResult(purchase, Outcome.IMPORTED));
            }
            InstallmentKey key = new InstallmentKey(purchase, number);
            confirmed.add(key);
            newConfirmations.add(key);
        }

        RecordImportedTransactions.Result recorded = created.isEmpty()
                ? new RecordImportedTransactions.Result(0, 0)
                : recordTransactions.executeOnCard(workspace, cardId, created);
        assignments.assignPurchases(card, purchases);
        confirmations.addAll(workspace, card.id(), newConfirmations, now);

        if (earliestEvidence != null && cards.recedeTrackingStart(workspace, card.id(), earliestEvidence)) {
            events.updated(card);
        }
        applyStatement(workspace, card, statement, fileMonth);

        log.info("Imported card statement recorded cardId={} workspaceId={} created={} reconciled={}", card.id(),
                workspace, created.size(), results.stream().filter(r -> r.outcome() == Outcome.RECONCILED).count());

        return new Result(List.copyOf(results), recorded.categorized(),
                created.stream().map(RecordImportedTransactions.CardLine::occurredOn).min(Comparator.naturalOrder()));
    }

    private CreditCard requireActive(WorkspaceId workspace, UUID cardId) {
        CreditCard card = cards.findById(workspace, new CardId(cardId)).orElseThrow(CardNotFoundException::new);
        card.ensureActive();
        return card;
    }

    /** Parcelas das compras {@code POSTED} do cartão, com o mês de fechamento da fatura de cada uma. */
    private List<Candidate> candidates(WorkspaceId workspace, CreditCard card, List<CardInvoice> rows) {
        Map<CardInvoiceId, YearMonth> months = new HashMap<>();
        rows.forEach(row -> months.put(row.id(), row.dates().closingMonth()));
        List<Candidate> candidates = new ArrayList<>();
        for (InstallmentFact fact : reader.facts(workspace, card.id()).installments()) {
            candidates.add(new Candidate(fact.transactionId(), fact.purchasedOn(), fact.number(), fact.count(),
                    months.get(fact.invoiceId()), fact.amount()));
        }
        return candidates;
    }

    /**
     * Vencimento e mínimo vão para a fatura que vence no mês informado; só o mínimo, para a fatura do arquivo. Sem
     * linha persistida (fatura sem lançamentos), nada a gravar.
     */
    private void applyStatement(WorkspaceId workspace, CreditCard card, Statement statement, YearMonth fileMonth) {
        if (statement.dueDate() == null && statement.minimumPayment() == null) {
            return;
        }
        for (CardInvoice row : invoices.findByCard(workspace, card.id())) {
            boolean target = statement.dueDate() != null
                    ? row.dates().dueMonth().equals(YearMonth.from(statement.dueDate()))
                    : row.dates().closingMonth().equals(fileMonth);
            if (target) {
                invoices.updateStatement(row.withStatement(statement.dueDate(), statement.minimumPayment()));
                return;
            }
        }
    }

    private static YearMonth earliest(YearMonth current, YearMonth candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }
}
