package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Edita os campos não financeiros. Valor, tipo, contas e data são imutáveis: corrigir um lançamento
 * é cancelar/estornar e lançar de novo (ADR-0005).
 */
@Service
public class UpdateTransaction {

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final Clock clock;

    public UpdateTransaction(TransactionAccess access, TransactionRepository transactions, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record Command(String description) {}

    @Transactional
    public TransactionView execute(UUID workspaceId, UUID transactionId, Command command) {
        Transaction transaction = access.requireTransaction(workspaceId, transactionId);

        transaction.updateDescription(new TransactionDescription(command.description()), clock.instant());
        transactions.save(transaction);

        return TransactionView.from(transaction);
    }
}
