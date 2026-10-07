package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.application.dto.TransactionPageView;
import com.smarthealthfinance.transactions.application.dto.TransactionView;
import com.smarthealthfinance.transactions.application.service.TransactionAccess;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import com.smarthealthfinance.transactions.domain.repository.TransactionPage;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/** Listagem paginada por offset (spec 05.6: MVP offset, pageSize ≤ 100), mais recentes primeiro. */
@Service
public class ListTransactions {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_TEXT_LENGTH = 100;

    private final TransactionAccess access;
    private final TransactionRepository transactions;

    public ListTransactions(TransactionAccess access, TransactionRepository transactions) {
        this.access = access;
        this.transactions = transactions;
    }

    /** Filtros nulos/em branco não filtram; {@code page} e {@code pageSize} nulos assumem os padrões. */
    public record Query(LocalDate from, LocalDate to, String type, String status, UUID accountId, String text,
                        Integer page, Integer pageSize) {}

    @Transactional(readOnly = true)
    public TransactionPageView execute(UUID workspaceId, Query query) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);

        int page = query.page() == null ? 0 : query.page();
        int pageSize = query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize();
        if (page < 0) {
            throw new InvalidValueException("page", "OUT_OF_RANGE");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidValueException("pageSize", "OUT_OF_RANGE");
        }

        TransactionPage result = transactions.search(workspace, toCriteria(query), page, pageSize);

        return new TransactionPageView(result.items().stream().map(TransactionView::from).toList(), page, pageSize,
                result.totalItems());
    }

    private static TransactionCriteria toCriteria(Query query) {
        if (query.from() != null && query.to() != null && query.to().isBefore(query.from())) {
            throw new InvalidValueException("to", "BEFORE_FROM");
        }

        String text = query.text() == null || query.text().isBlank() ? null : query.text().strip();
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidValueException("q", "TOO_LONG");
        }

        return new TransactionCriteria(
                query.from(),
                query.to(),
                isBlank(query.type()) ? null : TransactionType.parse(query.type()),
                isBlank(query.status()) ? null : TransactionStatus.parse(query.status()),
                query.accountId() == null ? null : new AccountId(query.accountId()),
                text);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
