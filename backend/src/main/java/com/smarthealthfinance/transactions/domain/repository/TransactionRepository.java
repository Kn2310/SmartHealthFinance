package com.smarthealthfinance.transactions.domain.repository;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.CardReference;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Toda operação é escopada pelo Workspace: transação de outro Workspace é indistinguível de inexistente. */
public interface TransactionRepository {

    Optional<Transaction> findById(WorkspaceId workspaceId, TransactionId transactionId);

    /** Bloqueia a linha até o fim da transação (serializa reembolsos concorrentes do mesmo original). */
    Optional<Transaction> findByIdForUpdate(WorkspaceId workspaceId, TransactionId transactionId);

    List<Transaction> findRefundsOf(WorkspaceId workspaceId, TransactionId originalId);

    /**
     * Pagamentos de fatura {@code POSTED} da conta para o cartão, com este valor, entre as datas (inclusive), que
     * ainda não absorveram um débito importado (ADR-0025 §7, adendo). Sem ordem garantida.
     */
    List<Transaction> findUnmatchedCardPayments(WorkspaceId workspaceId, AccountId accountId, CardReference card,
                                                Money amount, LocalDate from, LocalDate to);

    /** Mais recentes primeiro: occurredOn desc, createdAt desc, id desc. */
    TransactionPage search(WorkspaceId workspaceId, TransactionCriteria criteria, int page, int pageSize);

    /**
     * Transações que aceitam categoria e ainda esperam a categorização automática ou o comerciante (ADR-0013 §4),
     * em ordem de id, a partir de {@code after} (exclusivo; nulo = do início).
     */
    List<Transaction> findAwaitingAutomation(WorkspaceId workspaceId, TransactionId after, int limit);

    /**
     * Transações do comerciante cuja categoria uma regra pode mudar (ADR-0013 §3): sem escolha do usuário.
     * Só os tipos que aceitam a {@code nature}.
     */
    List<Transaction> findAutomatableByMerchant(WorkspaceId workspaceId, String merchantKey, CategoryNature nature);

    /** Workspaces com transações cujo comerciante veio de outra versão do normalizador (adendo ADR-0013 §1). */
    List<WorkspaceId> workspacesWithOutdatedMerchants(String currentVersion);

    /**
     * Transações com comerciante de outra versão do normalizador, em ordem de id, a partir de {@code after}
     * (exclusivo; nulo = do início).
     */
    List<Transaction> findWithOutdatedMerchant(WorkspaceId workspaceId, String currentVersion, TransactionId after,
                                               int limit);

    /** Alguma transação do Workspace, de um tipo que aceita a {@code nature}, ainda usa esta chave de comerciante. */
    boolean merchantKeyInUse(WorkspaceId workspaceId, String merchantKey, CategoryNature nature);

    void add(Transaction transaction);

    /** Inserção em lote de transações novas (importação, ADR-0009): sem leitura prévia por linha. */
    void addAll(List<Transaction> transactions);

    void save(Transaction transaction);
}
