package com.smarthealthfinance.transactions.application.service;

import com.smarthealthfinance.transactions.domain.model.Transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Request hash da Idempotency-Key (spec 05.6): SHA-256 dos campos de negócio já normalizados pelo domínio.
 * Ids e timestamps gerados a cada tentativa ficam de fora. Cada campo é prefixado pelo tamanho, para que
 * separadores dentro do texto livre não produzam colisões.
 */
public final class TransactionFingerprint {

    /** Versão do formato: mudar os campos exige nova versão (chaves antigas deixam de casar). */
    private static final String FORMAT = "v1";

    private TransactionFingerprint() {
    }

    public static String of(Transaction transaction) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, FORMAT);
        append(canonical, transaction.type().name());
        append(canonical, transaction.accountId().value());
        append(canonical, transaction.destinationAccountId().map(id -> id.value().toString()).orElse(null));
        append(canonical, transaction.adjustmentDirection().map(Enum::name).orElse(null));
        append(canonical, transaction.amount().toPlainString());
        append(canonical, transaction.amount().currency().getCurrencyCode());
        append(canonical, transaction.occurredOn());
        append(canonical, transaction.description().value());
        append(canonical, transaction.status().name());
        append(canonical, transaction.refundOfTransactionId().map(id -> id.value().toString()).orElse(null));
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder canonical, Object value) {
        if (value == null) {
            canonical.append("-;");
            return;
        }
        String text = Objects.toString(value);
        canonical.append(text.length()).append(':').append(text).append(';');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", ex);
        }
    }
}
