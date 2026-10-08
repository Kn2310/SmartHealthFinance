package com.smarthealthfinance.ingestion.application.service;

import com.smarthealthfinance.ingestion.application.port.StatementParser;
import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException;
import com.smarthealthfinance.ingestion.domain.model.ParsedStatement;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Escolhe o parser pela extensão e confere que o conteúdo concorda com ela (ADR-0009, Security): um OFX renomeado
 * para {@code .csv} é recusado em vez de virar lixo linha a linha.
 */
@Service
public class StatementFiles {

    private static final int SNIFF_BYTES = 4096;

    private final Map<ImportFormat, StatementParser> parsers = new EnumMap<>(ImportFormat.class);

    public StatementFiles(List<StatementParser> parsers) {
        parsers.forEach(parser -> this.parsers.put(parser.format(), parser));
    }

    public record ReadFile(ImportFormat format, ParsedStatement statement) {
    }

    /** @throws StatementRejectedException arquivo vazio, de outro tipo ou inválido como um todo */
    public ReadFile read(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new StatementRejectedException("EMPTY_FILE");
        }
        ImportFormat format = formatOf(fileName);
        if (format == ImportFormat.CSV && looksLikeOfx(content)) {
            throw new StatementRejectedException("FORMAT_MISMATCH");
        }
        StatementParser parser = parsers.get(format);
        if (parser == null) {
            throw new IllegalStateException("Sem parser para " + format);
        }
        return new ReadFile(format, parser.parse(content));
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível", impossible);
        }
    }

    private static ImportFormat formatOf(String fileName) {
        String name = fileName == null ? "" : fileName.strip().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            return ImportFormat.CSV;
        }
        if (name.endsWith(".ofx")) {
            return ImportFormat.OFX;
        }
        throw new StatementRejectedException("UNSUPPORTED_FILE_TYPE");
    }

    private static boolean looksLikeOfx(byte[] content) {
        String head = new String(content, 0, Math.min(content.length, SNIFF_BYTES), StandardCharsets.ISO_8859_1)
                .toUpperCase(Locale.ROOT);
        return head.contains("OFXHEADER") || head.contains("<OFX>");
    }
}
