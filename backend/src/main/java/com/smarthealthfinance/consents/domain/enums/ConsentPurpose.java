package com.smarthealthfinance.consents.domain.enums;

import com.smarthealthfinance.consents.domain.exception.ConsentTermsOutdatedException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Finalidades de consentimento (ADR-0038 §2), cada uma com a versão vigente do termo e o pré-requisito.
 * {@code INSIGHTS_GENERATION} está reservado e adiado: não existe aqui de propósito.
 */
public enum ConsentPurpose {

    COPILOT_DATA_USE("copilot-data-v1", null),
    COPILOT_HISTORY("copilot-history-v1", COPILOT_DATA_USE);

    private final String currentTermsVersion;
    private final ConsentPurpose prerequisite;

    ConsentPurpose(String currentTermsVersion, ConsentPurpose prerequisite) {
        this.currentTermsVersion = currentTermsVersion;
        this.prerequisite = prerequisite;
    }

    public String currentTermsVersion() {
        return currentTermsVersion;
    }

    public Optional<ConsentPurpose> prerequisite() {
        return Optional.ofNullable(prerequisite);
    }

    /** Finalidades que exigem esta ativa: revogá-la revoga as dependentes em cascata (ADR-0038 §3). */
    public List<ConsentPurpose> dependents() {
        return Arrays.stream(values()).filter(purpose -> purpose.prerequisite == this).toList();
    }

    /** @throws ConsentTermsOutdatedException se a versão informada não for a vigente */
    public void requireCurrentTerms(String termsVersion) {
        if (!currentTermsVersion.equals(termsVersion)) {
            throw new ConsentTermsOutdatedException(this);
        }
    }

    public static Optional<ConsentPurpose> parse(String value) {
        return Arrays.stream(values()).filter(purpose -> purpose.name().equals(value)).findFirst();
    }
}
