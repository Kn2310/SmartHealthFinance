package com.smarthealthfinance.identity.application.dto;

import com.smarthealthfinance.identity.domain.valueobject.ExternalIdentity;

public record AuthenticatedIdentity(
        ExternalIdentity externalIdentity, String email, String name, String preferredUsername
) {
}
