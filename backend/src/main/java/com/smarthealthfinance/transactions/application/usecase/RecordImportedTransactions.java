package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Grava lançamentos lidos de extrato (ADR-0009 §1/§10/§12). É a única porta de escrita do Ingestion no
 * Financial Core: as regras de transação continuam aqui.
 * <p>
 * Não autoriza usuário: roda no processamento assíncrono do batch, que foi autorizado na confirmação. O Workspace
 * vem do batch, e a conta é verificada de novo (pode ter sido arquivada depois do preview).
 * MANDATORY: as transações commitam junto com a deduplicação e o status do batch (tudo-ou-nada).
 */
@Service
public class RecordImportedTransactions {

    private static final Logger log = LoggerFactory.getLogger(RecordImportedTransactions.class);

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final Clock clock;

    public RecordImportedTransactions(TransactionAccess access, TransactionRepository transactions, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param id       atribuído pelo chamador, que já o vinculou à chave de deduplicação
     * @param inflow   true para entrada (INCOME), false para saída (EXPENSE)
     * @param amount   valor positivo
     */
    public record Line(TransactionId id, boolean inflow, Money amount, LocalDate occurredOn, String description) {
    }

    /**
     * @throws com.smarthealthfinance.accounts.application.exception.AccountNotFoundException conta fora do Workspace
     * @throws com.smarthealthfinance.accounts.domain.exception.AccountArchivedException conta arquivada
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(WorkspaceId workspace, AccountId accountId, List<Line> lines) {
        Account account = access.requireActiveAccount(workspace, accountId);
        Instant now = clock.instant();

        List<Transaction> imported = lines.stream().map(line -> {
            if (!account.currency().equals(line.amount().currency())) {
                throw new InvalidValueException("currency", "MISMATCH");
            }
            return Transaction.createImported(line.id(), workspace, accountId, line.inflow(), line.amount(),
                    line.occurredOn(), new TransactionDescription(line.description()), now);
        }).toList();

        transactions.addAll(imported);

        log.info("Imported transactions recorded workspaceId={} accountId={} count={}", workspace, accountId,
                imported.size());
    }
}
