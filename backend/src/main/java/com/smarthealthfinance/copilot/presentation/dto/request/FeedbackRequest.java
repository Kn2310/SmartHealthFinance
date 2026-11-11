package com.smarthealthfinance.copilot.presentation.dto.request;

import com.smarthealthfinance.copilot.domain.enums.FeedbackRating;
import com.smarthealthfinance.copilot.domain.enums.FeedbackReason;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Arrays;

/** Feedback de uma resposta (ADR-0040 §8): avaliação e motivo opcional, sem texto livre. */
@Schema(name = "CopilotFeedbackRequest")
public record FeedbackRequest(
        @Schema(allowableValues = {"HELPFUL", "NOT_HELPFUL"}) String rating,
        @Schema(allowableValues = {"WRONG_NUMBERS", "DID_NOT_ANSWER", "CONFUSING", "OTHER"}, nullable = true)
        String reason) {

    /** @throws InvalidValueException avaliação ausente ou fora do enum; motivo fora do enum */
    public FeedbackRating ratingValue() {
        if (rating == null || rating.isBlank()) {
            throw new InvalidValueException("rating", "REQUIRED");
        }
        return parse("rating", FeedbackRating.values(), rating);
    }

    public FeedbackReason reasonValue() {
        return reason == null || reason.isBlank() ? null : parse("reason", FeedbackReason.values(), reason);
    }

    private static <E extends Enum<E>> E parse(String field, E[] values, String value) {
        return Arrays.stream(values).filter(candidate -> candidate.name().equals(value.strip())).findFirst()
                .orElseThrow(() -> new InvalidValueException(field, "INVALID"));
    }
}
