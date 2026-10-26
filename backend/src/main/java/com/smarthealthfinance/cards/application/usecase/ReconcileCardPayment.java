package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.transactions.application.usecase.ReclassifyAsCardPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * "Marcar como pagamento de fatura" (ADR-0025 §7, adendo de 2026-10-23): o débito importado na conta vira o pagamento
 * deste cartão. A conta é a fonte do pagamento; o crédito no arquivo do cartão nunca vira transação (ADR-0009 §23).
 * Idempotente pelo estado: repetir com o mesmo cartão devolve o mesmo pagamento.
 */
@Service
public class ReconcileCardPayment {

    private static final Logger log = LoggerFactory.getLogger(ReconcileCardPayment.class);

    private final CardAccess access;
    private final ReclassifyAsCardPayment reclassify;

    public ReconcileCardPayment(CardAccess access, ReclassifyAsCardPayment reclassify) {
        this.access = access;
        this.reclassify = reclassify;
    }

    /**
     * @throws com.smarthealthfinance.cards.application.exception.CardNotFoundException cartão fora do Workspace
     * @throws com.smarthealthfinance.cards.domain.exception.CreditCardArchivedException cartão arquivado
     * @throws com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException débito fora do
     *         Workspace
     */
    @Transactional
    public ReclassifyAsCardPayment.Result execute(UUID workspaceId, UUID cardId, UUID transactionId) {
        CreditCard card = access.requireCard(workspaceId, cardId);
        card.ensureActive();

        ReclassifyAsCardPayment.Result result = reclassify.execute(card.workspaceId(), transactionId, cardId);
        if (!result.replayed()) {
            log.info("Card payment reconciled cardId={} paymentId={} matched={} workspaceId={}", card.id(),
                    result.payment().id(), result.matchedExisting(), card.workspaceId());
        }
        return result;
    }
}
