package com.smarthealthfinance.identity.presentation.dto.request;

import com.smarthealthfinance.identity.domain.valueobject.DisplayName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(@NotBlank @Size(max = DisplayName.MAX_LENGTH) String displayName) {
}
