package com.smarthealthfinance.consents.domain.model;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.enums.ConsentState;
import com.smarthealthfinance.consents.domain.enums.ConsentStatus;
import com.smarthealthfinance.consents.domain.exception.ConsentTermsOutdatedException;
import com.smarthealthfinance.consents.domain.valueobject.ConsentId;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentTest {

    private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final WorkspaceId WORKSPACE = new WorkspaceId(UUID.randomUUID());
    private static final UserId USER = new UserId(UUID.randomUUID());

    private static Consent granted() {
        return Consent.grant(ConsentId.generate(T0), WORKSPACE, USER, ConsentPurpose.COPILOT_DATA_USE,
                "copilot-data-v1", T0);
    }

    @Test
    void grantIsActiveInTheCurrentTerms() {
        Consent consent = granted();

        assertThat(consent.state()).isEqualTo(ConsentState.ACTIVE);
        assertThat(consent.isActive()).isTrue();
        assertThat(consent.grantedAt()).isEqualTo(T0);
        assertThat(consent.revokedAt()).isEmpty();
    }

    @Test
    void grantRequiresTheCurrentTerms() {
        assertThatThrownBy(() -> Consent.grant(ConsentId.generate(T0), WORKSPACE, USER,
                ConsentPurpose.COPILOT_DATA_USE, "copilot-data-v0", T0))
                .isInstanceOf(ConsentTermsOutdatedException.class);
        assertThatThrownBy(() -> Consent.grant(ConsentId.generate(T0), WORKSPACE, USER,
                ConsentPurpose.COPILOT_DATA_USE, null, T0))
                .isInstanceOf(ConsentTermsOutdatedException.class);
    }

    @Test
    void revokeIsIdempotentAndKeepsTheTermsVersion() {
        Consent consent = granted();

        assertThat(consent.revoke(T1)).isTrue();
        assertThat(consent.revoke(T1.plusSeconds(1))).isFalse();
        assertThat(consent.state()).isEqualTo(ConsentState.REVOKED);
        assertThat(consent.revokedAt()).contains(T1);
        assertThat(consent.termsVersion()).isEqualTo("copilot-data-v1");
    }

    @Test
    void regrantAfterRevocationResetsTheDates() {
        Consent consent = granted();
        consent.revoke(T1);

        assertThat(consent.regrant("copilot-data-v1", T1.plusSeconds(60))).isTrue();
        assertThat(consent.state()).isEqualTo(ConsentState.ACTIVE);
        assertThat(consent.grantedAt()).isEqualTo(T1.plusSeconds(60));
        assertThat(consent.revokedAt()).isEmpty();
    }

    @Test
    void regrantOfAnActiveConsentChangesNothing() {
        Consent consent = granted();

        assertThat(consent.regrant("copilot-data-v1", T1)).isFalse();
        assertThat(consent.grantedAt()).isEqualTo(T0);
    }

    @Test
    void anOlderTermsVersionIsExpiredAndNotActive() {
        Consent consent = Consent.restore(ConsentId.generate(T0), WORKSPACE, USER, ConsentPurpose.COPILOT_DATA_USE,
                ConsentStatus.ACTIVE, "copilot-data-v0", T0, null, T0, T0, 0);

        assertThat(consent.state()).isEqualTo(ConsentState.EXPIRED);
        assertThat(consent.isActive()).isFalse();
        assertThat(consent.regrant("copilot-data-v1", T1)).isTrue();
        assertThat(consent.state()).isEqualTo(ConsentState.ACTIVE);
    }

    @Test
    void historyDependsOnDataUse() {
        assertThat(ConsentPurpose.COPILOT_HISTORY.prerequisite()).contains(ConsentPurpose.COPILOT_DATA_USE);
        assertThat(ConsentPurpose.COPILOT_DATA_USE.prerequisite()).isEmpty();
        assertThat(ConsentPurpose.COPILOT_DATA_USE.dependents()).containsExactly(ConsentPurpose.COPILOT_HISTORY);
        assertThat(ConsentPurpose.parse("INSIGHTS_GENERATION")).isEmpty();
    }
}
