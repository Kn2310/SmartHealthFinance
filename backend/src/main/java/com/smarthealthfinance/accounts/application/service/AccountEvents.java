package com.smarthealthfinance.accounts.application.service;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Evento {@code account.changed} v1 (ADR-0018 §2), gravado na outbox na mesma transação do fato, só quando a conta
 * realmente mudou: criada, editada, arquivada ou reativada. O payload carrega só o id; o consumidor relê a conta.
 */
@Service
public class AccountEvents {

    public static final String CHANGED = "account.changed";
    public static final int VERSION = 1;

    private final EventOutbox outbox;

    public AccountEvents(EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void changed(Account account) {
        outbox.append(new IntegrationEvent(CHANGED, VERSION, account.workspaceId().value(), "Account",
                account.id().value(), null, Map.of("accountId", account.id().value().toString())));
    }
}
