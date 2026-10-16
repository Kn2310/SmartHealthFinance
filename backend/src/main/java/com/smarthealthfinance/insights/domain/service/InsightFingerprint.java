package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Identidade estável de um insight (ADR-0021 §5): SHA-256 hex de {@code detector|workspaceId|período|sujeito}, sem a
 * versão do catálogo (uma versão nova nunca ressuscita um dispensado). O hash não deixa ler o sujeito.
 */
public final class InsightFingerprint {

    private InsightFingerprint() {
    }

    /** @param periodKey nulo nos detectores sem período (D9) */
    public static String of(InsightDetector detector, UUID workspaceId, String periodKey, String subject) {
        String raw = detector.name() + "|" + workspaceId + "|" + (periodKey == null ? "-" : periodKey) + "|" + subject;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível", impossible);
        }
    }
}
