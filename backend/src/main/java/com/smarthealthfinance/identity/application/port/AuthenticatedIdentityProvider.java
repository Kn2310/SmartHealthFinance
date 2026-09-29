package com.smarthealthfinance.identity.application.port;

import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;

public interface AuthenticatedIdentityProvider {
    AuthenticatedIdentity current();
}
