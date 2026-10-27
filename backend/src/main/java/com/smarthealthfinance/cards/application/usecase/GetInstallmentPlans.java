package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.InstallmentPlanView;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.application.service.BusinessCalendar;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardLedgerLoader;
import com.smarthealthfinance.cards.domain.enums.InstallmentPlanStatus;
import com.smarthealthfinance.cards.domain.enums.InstallmentStatus;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Charge;
import com.smarthealthfinance.cards.domain.service.InvoiceLedger;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parcelamentos do cartão (ADR-0026 §10): status de cada parcela pelo estado da fatura em que cai e pelos estornos
 * (adendo do ADR-0025 §8). Só compras {@code POSTED}; à vista também é um "parcelamento" de 1, mas a lista mostra só
 * compras em mais de uma parcela.
 */
@Service
public class GetInstallmentPlans {

    private final CardAccess access;
    private final CardLedgerLoader loader;
    private final BusinessCalendar calendar;

    public GetInstallmentPlans(CardAccess access, CardLedgerLoader loader, BusinessCalendar calendar) {
        this.access = access;
        this.loader = loader;
        this.calendar = calendar;
    }

    /** @param status {@code ACTIVE} (padrão), {@code FINISHED} (inclui cancelados) ou {@code ALL} */
    @Transactional(readOnly = true)
    public List<InstallmentPlanView> list(UUID workspaceId, UUID cardId, String status) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        String filter = status == null || status.isBlank() ? "ACTIVE" : status.strip();
        if (!List.of("ACTIVE", "FINISHED", "ALL").contains(filter)) {
            throw new InvalidValueException("status", "INVALID");
        }

        CardLedgerSnapshot snapshot = loader.load(card, calendar.today());
        return plans(snapshot).values().stream()
                .filter(plan -> plan.count() > 1)
                .filter(plan -> switch (filter) {
                    case "ACTIVE" -> plan.status() == InstallmentPlanStatus.ACTIVE;
                    case "FINISHED" -> plan.status() != InstallmentPlanStatus.ACTIVE;
                    default -> true;
                })
                .sorted(Comparator.comparing(InstallmentPlanView::purchasedOn).reversed()
                        .thenComparing(plan -> plan.transactionId().toString()))
                .toList();
    }

    /** @throws TransactionNotFoundException se a compra não for deste cartão (inclusive de outro Workspace) */
    @Transactional(readOnly = true)
    public InstallmentPlanView get(UUID workspaceId, UUID cardId, UUID transactionId) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        InstallmentPlanView plan = plans(loader.load(card, calendar.today())).get(transactionId);
        if (plan == null) {
            throw new TransactionNotFoundException();
        }
        return plan;
    }

    private static Map<UUID, InstallmentPlanView> plans(CardLedgerSnapshot snapshot) {
        Map<UUID, List<Integer>> indexes = new LinkedHashMap<>();
        List<InstallmentFact> facts = snapshot.facts().installments();
        for (int i = 0; i < facts.size(); i++) {
            indexes.computeIfAbsent(facts.get(i).transactionId(), ignored -> new ArrayList<>()).add(i);
        }

        Map<UUID, InstallmentPlanView> plans = new LinkedHashMap<>();
        indexes.forEach((transactionId, positions) -> plans.put(transactionId, plan(snapshot, positions)));
        return plans;
    }

    private static InstallmentPlanView plan(CardLedgerSnapshot snapshot, List<Integer> positions) {
        Money zero = Money.zero(snapshot.card().limit().value().currency());
        List<InstallmentFact> facts = snapshot.facts().installments();
        InstallmentFact first = facts.get(positions.getFirst());

        List<InstallmentPlanView.Item> items = new ArrayList<>();
        Money total = zero;
        Money paid = zero;
        Money future = zero;
        int paidCount = 0;
        Integer current = null;
        for (int position : positions) {
            InstallmentFact fact = facts.get(position);
            Charge charge = snapshot.charges().get(position);
            Money cancelled = snapshot.allocation().cancelledOf(charge);
            Money net = fact.amount().minus(cancelled);
            InstallmentStatus status = snapshot.isBeforeTracking(charge.closingMonth())
                    ? InstallmentStatus.BEFORE_TRACKING
                    : statusOf(net, snapshot.statusOf(charge.closingMonth()));

            total = total.plus(fact.amount());
            switch (status) {
                case PAID -> {
                    paidCount++;
                    paid = paid.plus(net);
                }
                case CURRENT -> current = current == null ? fact.number() : Math.min(current, fact.number());
                case FUTURE -> future = future.plus(net);
                case CANCELLED, BEFORE_TRACKING -> {
                }
            }
            items.add(new InstallmentPlanView.Item(fact.number(),
                    snapshot.calendar().datesOf(charge.closingMonth()).dueMonth(), fact.amount(), cancelled, status));
        }
        items.sort(Comparator.comparingInt(InstallmentPlanView.Item::number));

        // O que ainda fica reservado do limite em cada fatura do parcelamento: ela e as seguintes, se não pagas.
        List<InstallmentPlanView.Reserved> reserved = new ArrayList<>();
        for (InstallmentPlanView.Item item : items) {
            Money amount = items.stream()
                    .filter(other -> !other.dueMonth().isBefore(item.dueMonth()))
                    .filter(other -> other.status() == InstallmentStatus.CURRENT
                            || other.status() == InstallmentStatus.FUTURE)
                    .map(other -> other.amount().minus(other.cancelledAmount()))
                    .reduce(zero, Money::plus);
            reserved.add(new InstallmentPlanView.Reserved(item.dueMonth(), amount));
        }

        boolean allCancelled = items.stream().allMatch(item -> item.status() == InstallmentStatus.CANCELLED);
        boolean open = items.stream().anyMatch(item -> item.status() == InstallmentStatus.CURRENT
                || item.status() == InstallmentStatus.FUTURE);
        InstallmentPlanStatus planStatus = allCancelled ? InstallmentPlanStatus.CANCELLED
                : open ? InstallmentPlanStatus.ACTIVE : InstallmentPlanStatus.FINISHED;
        YearMonth last = items.getLast().dueMonth();

        // Peso na fatura aberta (ADR-0029 §5): a parte desta compra nela ÷ o valor dela, pela regra do razão.
        InvoiceStatement openInvoice = snapshot.currentStatement();
        YearMonth openDue = openInvoice.dates().dueMonth();
        Money inOpen = items.stream()
                .filter(item -> item.dueMonth().equals(openDue) && item.status() == InstallmentStatus.CURRENT)
                .map(item -> item.amount().minus(item.cancelledAmount()))
                .reduce(zero, Money::plus);
        InstallmentPlanView.CurrentInvoiceShare share = inOpen.isPositive() && openInvoice.amount().isPositive()
                ? new InstallmentPlanView.CurrentInvoiceShare(openDue, inOpen, openInvoice.amount(),
                        InvoiceLedger.percent(inOpen, openInvoice.amount()))
                : null;

        return new InstallmentPlanView(first.transactionId(), first.description(), first.purchasedOn(), total,
                first.count(), items.getFirst().amount(), paidCount, paid, current, future, last, planStatus,
                List.copyOf(items), List.copyOf(reserved), share);
    }

    private static InstallmentStatus statusOf(Money net, InvoiceStatus invoice) {
        if (!net.isPositive()) {
            return InstallmentStatus.CANCELLED;
        }
        return switch (invoice) {
            case PAID -> InstallmentStatus.PAID;
            case FUTURE -> InstallmentStatus.FUTURE;
            case OPEN, CLOSED, PARTIALLY_PAID, OVERDUE -> InstallmentStatus.CURRENT;
        };
    }
}
