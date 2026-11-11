package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.application.port.CopilotPrompts;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Prompts e esquemas versionados em {@code resources/copilot} (ADR-0039 §11), lidos uma vez no boot com SHA-256. Um
 * arquivo faltando impede a API de subir.
 */
@Component
public class ClasspathCopilotPrompts implements CopilotPrompts {

    public static final String INTENT_PROMPT = "intent-v1";
    public static final String ANSWER_PROMPT = "answer-v1";
    public static final String INTENT_SCHEMA = "intents-v1";
    public static final String ANSWER_SCHEMA = "answer-v1";

    private final Versioned intentPrompt = load("copilot/prompts/", INTENT_PROMPT, ".md");
    private final Versioned answerPrompt = load("copilot/prompts/", ANSWER_PROMPT, ".md");
    private final Versioned intentSchema = load("copilot/schemas/", INTENT_SCHEMA, ".json");
    private final Versioned answerSchema = load("copilot/schemas/", ANSWER_SCHEMA, ".json");

    @Override
    public Versioned intentPrompt() {
        return intentPrompt;
    }

    @Override
    public Versioned answerPrompt() {
        return answerPrompt;
    }

    @Override
    public Versioned intentSchema() {
        return intentSchema;
    }

    @Override
    public Versioned answerSchema() {
        return answerSchema;
    }

    static Versioned load(String folder, String name, String extension) {
        try (InputStream in = new ClassPathResource(folder + name + extension).getInputStream()) {
            // Quebras de linha normalizadas: o hash é o mesmo num checkout Windows (CRLF) e na CI (LF).
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return new Versioned(name, text, HexFormat.of().formatHex(digest));
        } catch (IOException e) {
            throw new UncheckedIOException("Copilot resource missing: " + folder + name + extension, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
