package com.smarthealthfinance.transactions.infrastructure.persistence.adapter;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import com.smarthealthfinance.transactions.domain.repository.TransactionPage;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import com.smarthealthfinance.transactions.infrastructure.persistence.entity.TransactionJpaEntity;
import com.smarthealthfinance.transactions.infrastructure.persistence.repository.TransactionJpaRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class JpaTransactionRepository implements TransactionRepository {

    /** Mesma ordem do índice ix_transactions_workspace_occurred. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredOn"), Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    private static final char LIKE_ESCAPE = '\\';

    private final TransactionJpaRepository jpa;

    public JpaTransactionRepository(TransactionJpaRepository jpa) {
        this.jpa = jpa;
    }

    /** Sempre filtrado pelo Workspace: transação de outro Workspace é indistinguível de inexistente. */
    @Override
    public Optional<Transaction> findById(WorkspaceId workspaceId, TransactionId transactionId) {
        return jpa.findByIdAndWorkspaceId(transactionId.value(), workspaceId.value())
                .map(TransactionJpaEntity::toDomain);
    }

    /** SELECT ... FOR UPDATE: exige transação ativa (o caso de uso é @Transactional). */
    @Override
    public Optional<Transaction> findByIdForUpdate(WorkspaceId workspaceId, TransactionId transactionId) {
        return jpa.findForUpdate(transactionId.value(), workspaceId.value()).map(TransactionJpaEntity::toDomain);
    }

    @Override
    public List<Transaction> findRefundsOf(WorkspaceId workspaceId, TransactionId originalId) {
        return jpa.findAllByWorkspaceIdAndRefundOfTransactionId(workspaceId.value(), originalId.value()).stream()
                .map(TransactionJpaEntity::toDomain)
                .toList();
    }

    @Override
    public TransactionPage search(WorkspaceId workspaceId, TransactionCriteria criteria, int page, int pageSize) {
        Page<TransactionJpaEntity> result = jpa.findAll(matching(workspaceId, criteria),
                PageRequest.of(page, pageSize, NEWEST_FIRST));

        return new TransactionPage(result.map(TransactionJpaEntity::toDomain).getContent(),
                result.getTotalElements());
    }

    @Override
    public void add(Transaction transaction) {
        jpa.save(TransactionJpaEntity.from(transaction));
    }

    /** O version vindo do domínio faz o merge falhar com OptimisticLockingFailureException se estiver obsoleto. */
    @Override
    public void save(Transaction transaction) {
        jpa.save(TransactionJpaEntity.from(transaction));
    }

    private static Specification<TransactionJpaEntity> matching(WorkspaceId workspaceId, TransactionCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspaceId"), workspaceId.value()));

            if (criteria.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("occurredOn"), criteria.from()));
            }
            if (criteria.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("occurredOn"), criteria.to()));
            }
            if (criteria.type() != null) {
                predicates.add(cb.equal(root.get("type"), criteria.type()));
            }
            if (criteria.status() != null) {
                predicates.add(cb.equal(root.get("status"), criteria.status()));
            }
            if (criteria.accountId() != null) {
                predicates.add(cb.or(
                        cb.equal(root.get("accountId"), criteria.accountId().value()),
                        cb.equal(root.get("destinationAccountId"), criteria.accountId().value())));
            }
            if (criteria.text() != null) {
                String pattern = "%" + escapeLike(criteria.text().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.like(cb.lower(root.<String>get("description")), pattern, LIKE_ESCAPE));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** O texto do usuário é literal: %, _ e o próprio escape não podem virar curingas. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
