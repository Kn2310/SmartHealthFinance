package com.smarthealthfinance.consents.application.dto;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.enums.ConsentState;
import com.smarthealthfinance.consents.domain.model.Consent;

import java.time.Instant;
import java.util.List;

/** Uma finalidade como a tela a vê; sem linha = {@code PENDING} (ADR-0038 §7). */
public record ConsentView(ConsentPurpose purpose, ConsentState state, String termsVersion,
                          String currentTermsVersion, Instant grantedAt, Instant revokedAt,
                          List<ConsentPurpose> requires) {

    public static ConsentView pending(ConsentPurpose purpose) {
        return new ConsentView(purpose, ConsentState.PENDING, null, purpose.currentTermsVersion(), null, null,
                requires(purpose));
    }

    public static ConsentView from(Consent consent) {
        return new ConsentView(consent.purpose(), consent.state(), consent.termsVersion(),
                consent.purpose().currentTermsVersion(), consent.grantedAt(), consent.revokedAt().orElse(null),
                requires(consent.purpose()));
    }

    private static List<ConsentPurpose> requires(ConsentPurpose purpose) {
        return purpose.prerequisite().stream().toList();
    }
}
