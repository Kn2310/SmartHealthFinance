package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** POSTED → REVERSED. Idempotente. O fato original permanece para auditoria. */
@Service
public class ReverseTransaction {

    private static final Logger log = LoggerFactory.getLogger(ReverseTransaction.class);

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final Clock clock;

    public ReverseTransaction(TransactionAccess access, TransactionRepository transactions, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional
    public TransactionView execute(UUID workspaceId, UUID transactionId) {
        Transaction transaction = access.requireTransaction(workspaceId, transactionId);
        TransactionStatus before = transaction.status();

        transaction.reverse(clock.instant());

        if (transaction.status() != before) {
            transactions.save(transaction);
            log.info("Transaction reversed transactionId={} workspaceId={}", transaction.id(),
                    transaction.workspaceId());
        }

        return TransactionView.from(transaction);
    }
}
