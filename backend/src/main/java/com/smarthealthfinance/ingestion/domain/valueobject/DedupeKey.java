package com.smarthealthfinance.ingestion.domain.valueobject;

import com.smarthealthfinance.shared.domain.Money;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Chave de deduplicação de um lançamento importado, sempre por conta (ADR-0009 §11). Formato versionado ({@code v1}):
 * mudar a fórmula exige nova versão, senão reimportações antigas deixam de ser reconhecidas.
 *
 * @param value SHA-256 em hexadecimal minúsculo
 */
public record DedupeKey(String value) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public DedupeKey {
        Objects.requireNonNull(value, "value");
        if (!SHA256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("DedupeKey deve ser um SHA-256 hexadecimal");
        }
    }

    /**
     * Com identificador do banco. Data e valor entram porque há bancos que reutilizam {@code FITID} para
     * lançamentos distintos.
     */
    public static DedupeKey ofExternalId(String externalId, LocalDate occurredOn, Money signedAmount) {
        Objects.requireNonNull(externalId, "externalId");
        return hash("v1|ext|" + externalId + "|" + occurredOn + "|" + canonical(signedAmount));
    }

    /**
     * Sem identificador: a ordem da ocorrência da mesma tupla no arquivo mantém distintos dois lançamentos
     * idênticos no mesmo dia e faz um extrato sobreposto produzir as mesmas chaves.
     *
     * @param occurrence 1 para a primeira ocorrência da tupla no arquivo, 2 para a segunda...
     */
    public static DedupeKey ofFingerprint(LocalDate occurredOn, Money signedAmount, String description,
                                          int occurrence) {
        if (occurrence < 1) {
            throw new IllegalArgumentException("occurrence começa em 1");
        }
        return hash("v1|fp|" + occurredOn + "|" + canonical(signedAmount) + "|" + normalize(description) + "|"
                + occurrence);
    }

    /** Descrição comparável: sem acentos, maiúscula e com espaços colapsados. */
    public static String normalize(String description) {
        String withoutAccents = Normalizer.normalize(description, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return WHITESPACE.matcher(withoutAccents.strip()).replaceAll(" ").toUpperCase(Locale.ROOT);
    }

    /** Escala fixa do Money (4 casas): "86.4" e "86.40" geram a mesma chave. */
    private static String canonical(Money signedAmount) {
        return signedAmount.amount().toPlainString();
    }

    private static DedupeKey hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return new DedupeKey(HexFormat.of().formatHex(digest));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível", impossible);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
