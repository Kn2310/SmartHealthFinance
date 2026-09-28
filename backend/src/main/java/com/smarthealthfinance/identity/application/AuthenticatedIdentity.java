package com.smarthealthfinance.identity.application;

import com.smarthealthfinance.identity.domain.ExternalIdentity;

public record AuthenticatedIdentity(
        ExternalIdentity externalIdentity, String email, String name, String preferredUsername
) {
}
