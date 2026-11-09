package com.smarthealthfinance.consents.application.usecase;

import com.smarthealthfinance.consents.application.ConsentsTestContext;
import com.smarthealthfinance.consents.application.dto.ConsentView;
import com.smarthealthfinance.consents.application.exception.ConsentPurposeNotFoundException;
import com.smarthealthfinance.consents.domain.enums.ConsentAction;
import com.smarthealthfinance.consents.domain.enums.ConsentCause;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.enums.ConsentState;
import com.smarthealthfinance.consents.domain.exception.ConsentPrerequisiteMissingException;
import com.smarthealthfinance.consents.domain.exception.ConsentTermsOutdatedException;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static com.smarthealthfinance.consents.application.ConsentsTestContext.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ChangeConsentTest {

    private static final ChangeConsent.Command GRANT_DATA = new ChangeConsent.Command(true, "copilot-data-v1");
    private static final ChangeConsent.Command GRANT_HISTORY = new ChangeConsent.Command(true, "copilot-history-v1");
    private static final ChangeConsent.Command REVOKE = new ChangeConsent.Command(false, null);

    private final ConsentsTestContext ctx = new ConsentsTestContext();

    private List<ConsentView> change(String purpose, ChangeConsent.Command command) {
        return ctx.change().execute(ctx.anasWorkspaceId(), purpose, command);
    }

    private static ConsentView of(List<ConsentView> views, ConsentPurpose purpose) {
        return views.stream().filter(v -> v.purpose() == purpose).findFirst().orElseThrow();
    }

    @Test
    void everythingStartsPendingOptIn() {
        List<ConsentView> views = ctx.list.execute(ctx.anasWorkspaceId());

        assertThat(views).extracting(ConsentView::purpose)
                .containsExactly(ConsentPurpose.COPILOT_DATA_USE, ConsentPurpose.COPILOT_HISTORY);
        assertThat(views).extracting(ConsentView::state).containsOnly(ConsentState.PENDING);
        assertThat(of(views, ConsentPurpose.COPILOT_HISTORY).requires())
                .containsExactly(ConsentPurpose.COPILOT_DATA_USE);
        assertThat(of(views, ConsentPurpose.COPILOT_DATA_USE).currentTermsVersion()).isEqualTo("copilot-data-v1");
    }

    @Test
    void grantIsAuditedEmitsAnEventAndShowsInTheList(CapturedOutput output) {
        List<ConsentView> views = change("COPILOT_DATA_USE", GRANT_DATA);

        ConsentView data = of(views, ConsentPurpose.COPILOT_DATA_USE);
        assertThat(data.state()).isEqualTo(ConsentState.ACTIVE);
        assertThat(data.grantedAt()).isEqualTo(NOW);
        assertThat(data.termsVersion()).isEqualTo("copilot-data-v1");
        assertThat(ctx.consents.audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo(ConsentAction.GRANTED);
            assertThat(entry.cause()).isEqualTo(ConsentCause.USER);
        });
        IntegrationEvent event = ctx.outbox.getFirst();
        assertThat(event.eventType()).isEqualTo("consent.granted");
        assertThat(event.workspaceId()).isEqualTo(ctx.anasWorkspaceId());
        assertThat(event.payload()).containsEntry("purpose", "COPILOT_DATA_USE")
                .containsEntry("termsVersion", "copilot-data-v1").containsEntry("cause", "USER")
                .containsEntry("userId", ctx.ana.id().value().toString())
                .containsOnlyKeys("consentId", "userId", "purpose", "termsVersion", "cause");
        assertThat(ctx.reader.isActive(ctx.anasWorkspace.id(), ctx.ana.id(), ConsentPurpose.COPILOT_DATA_USE))
                .isTrue();
        assertThat(ctx.meters.counter("shf.consents.changes", "purpose", "COPILOT_DATA_USE", "action", "GRANTED",
                "cause", "USER").count()).isEqualTo(1.0);
        assertThat(output).doesNotContain(ctx.ana.email().value());
    }

    @Test
    void repeatingTheCurrentStateIsANoOp() {
        change("COPILOT_DATA_USE", GRANT_DATA);
        change("COPILOT_DATA_USE", GRANT_DATA);
        change("COPILOT_HISTORY", REVOKE);

        assertThat(ctx.consents.audit).hasSize(1);
        assertThat(ctx.outbox).hasSize(1);
        assertThat(ctx.consents.size()).isEqualTo(1);
    }

    @Test
    void grantRequiresTheCurrentTermsVersion() {
        assertThatThrownBy(() -> change("COPILOT_DATA_USE", new ChangeConsent.Command(true, null)))
                .isInstanceOf(ConsentTermsOutdatedException.class);
        assertThatThrownBy(() -> change("COPILOT_DATA_USE", new ChangeConsent.Command(true, "copilot-data-v0")))
                .isInstanceOf(ConsentTermsOutdatedException.class);
        assertThat(ctx.consents.size()).isZero();
        assertThat(ctx.outbox).isEmpty();
    }

    @Test
    void historyRequiresActiveDataUse() {
        assertThatThrownBy(() -> change("COPILOT_HISTORY", GRANT_HISTORY))
                .isInstanceOf(ConsentPrerequisiteMissingException.class);

        change("COPILOT_DATA_USE", GRANT_DATA);
        List<ConsentView> views = change("COPILOT_HISTORY", GRANT_HISTORY);

        assertThat(of(views, ConsentPurpose.COPILOT_HISTORY).state()).isEqualTo(ConsentState.ACTIVE);
    }

    @Test
    void revokingDataUseRevokesHistoryInCascade() {
        change("COPILOT_DATA_USE", GRANT_DATA);
        change("COPILOT_HISTORY", GRANT_HISTORY);
        ctx.outbox.clear();
        ctx.consents.audit.clear();

        List<ConsentView> views = change("COPILOT_DATA_USE", REVOKE);

        assertThat(views).extracting(ConsentView::state).containsOnly(ConsentState.REVOKED);
        assertThat(ctx.consents.audit).extracting(e -> e.consent().purpose(), e -> e.action(), e -> e.cause())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ConsentPurpose.COPILOT_DATA_USE, ConsentAction.REVOKED,
                                ConsentCause.USER),
                        org.assertj.core.groups.Tuple.tuple(ConsentPurpose.COPILOT_HISTORY, ConsentAction.REVOKED,
                                ConsentCause.CASCADE));
        assertThat(ctx.outbox).extracting(IntegrationEvent::eventType)
                .containsExactly("consent.revoked", "consent.revoked");
        assertThat(ctx.outbox.get(1).payload()).containsEntry("purpose", "COPILOT_HISTORY")
                .containsEntry("cause", "CASCADE");
        assertThat(ctx.reader.isActive(ctx.anasWorkspace.id(), ctx.ana.id(), ConsentPurpose.COPILOT_DATA_USE))
                .isFalse();
    }

    @Test
    void regrantAfterRevocationIsANewGrant() {
        change("COPILOT_DATA_USE", GRANT_DATA);
        change("COPILOT_DATA_USE", REVOKE);
        ctx.clock = Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC);

        ConsentView data = of(change("COPILOT_DATA_USE", GRANT_DATA), ConsentPurpose.COPILOT_DATA_USE);

        assertThat(data.state()).isEqualTo(ConsentState.ACTIVE);
        assertThat(data.grantedAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(data.revokedAt()).isNull();
        assertThat(ctx.consents.size()).isEqualTo(1);
        assertThat(ctx.consents.audit).extracting(e -> e.action())
                .containsExactly(ConsentAction.GRANTED, ConsentAction.REVOKED, ConsentAction.GRANTED);
    }

    @Test
    void unknownPurposeIsNotFound() {
        assertThatThrownBy(() -> change("INSIGHTS_GENERATION", GRANT_DATA))
                .isInstanceOf(ConsentPurposeNotFoundException.class);
    }

    @Test
    void consentsArePerPersonAndWorkspace() {
        change("COPILOT_DATA_USE", GRANT_DATA);

        ctx.actAs(ctx.bob);
        assertThatThrownBy(() -> ctx.change().execute(ctx.anasWorkspaceId(), "COPILOT_DATA_USE", REVOKE))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> ctx.list.execute(ctx.anasWorkspaceId()))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(ctx.list.execute(ctx.bobsWorkspace.id().value())).extracting(ConsentView::state)
                .containsOnly(ConsentState.PENDING);
        assertThat(ctx.reader.isActive(ctx.anasWorkspace.id(), ctx.bob.id(), ConsentPurpose.COPILOT_DATA_USE))
                .isFalse();
        assertThat(ctx.reader.isActive(ctx.anasWorkspace.id(), ctx.ana.id(), ConsentPurpose.COPILOT_DATA_USE))
                .isTrue();
    }
}
