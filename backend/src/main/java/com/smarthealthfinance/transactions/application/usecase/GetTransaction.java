package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GetTransaction {

    private final TransactionAccess access;

    public GetTransaction(TransactionAccess access) {
        this.access = access;
    }

    @Transactional(readOnly = true)
    public TransactionView execute(UUID workspaceId, UUID transactionId) {
        return TransactionView.from(access.requireTransaction(workspaceId, transactionId));
    }
}
