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

/** PENDING → POSTED. Idempotente. Efetivar movimento exige contas ativas, como na criação. */
@Service
public class PostTransaction {

    private static final Logger log = LoggerFactory.getLogger(PostTransaction.class);

    private final TransactionAccess access;
    private final TransactionRepository transactions;
    private final Clock clock;

    public PostTransaction(TransactionAccess access, TransactionRepository transactions, Clock clock) {
        this.access = access;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional
    public TransactionView execute(UUID workspaceId, UUID transactionId) {
        Transaction transaction = access.requireTransaction(workspaceId, transactionId);
        TransactionStatus before = transaction.status();

        if (before == TransactionStatus.PENDING) {
            access.requireActiveAccount(transaction.workspaceId(), transaction.accountId());
            transaction.destinationAccountId()
                    .ifPresent(destination -> access.requireActiveAccount(transaction.workspaceId(), destination));
        }

        transaction.post(clock.instant());

        if (transaction.status() != before) {
            transactions.save(transaction);
            log.info("Transaction posted transactionId={} workspaceId={}", transaction.id(),
                    transaction.workspaceId());
        }

        return TransactionView.from(transaction);
    }
}
