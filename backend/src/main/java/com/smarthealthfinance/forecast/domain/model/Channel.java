package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.ChannelKind;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** A conta ou o cartão por onde passa uma transação ou uma série (ADR-0030 §2). */
public record Channel(ChannelKind kind, UUID id) {

    /** Ordem determinística para desempates: conta antes de cartão, depois pelo id. */
    public static final Comparator<Channel> ORDER =
            Comparator.comparing(Channel::kind).thenComparing(Channel::id);

    public Channel {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    public static Channel account(UUID id) {
        return new Channel(ChannelKind.ACCOUNT, id);
    }

    public static Channel card(UUID id) {
        return new Channel(ChannelKind.CARD, id);
    }

    public boolean isCard() {
        return kind == ChannelKind.CARD;
    }
}
