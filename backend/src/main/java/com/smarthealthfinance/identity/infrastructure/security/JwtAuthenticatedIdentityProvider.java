package com.smarthealthfinance.identity.infrastructure.security;

import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.port.AuthenticatedIdentityProvider;
import com.smarthealthfinance.identity.domain.valueobject.ExternalIdentity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class JwtAuthenticatedIdentityProvider implements AuthenticatedIdentityProvider {

    @Override
    public AuthenticatedIdentity current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AuthenticationCredentialsNotFoundException("JWT authentication required");
        }

        Jwt jwt = token.getToken();
        String issuer = jwt.getClaimAsString(JwtClaimNames.ISS);
        String subject = jwt.getSubject();

        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new AuthenticationCredentialsNotFoundException("Token without iss/sub");
        }

        return new AuthenticatedIdentity(new ExternalIdentity(issuer, subject),
                jwt.getClaimAsString(StandardClaimNames.EMAIL),
                jwt.getClaimAsString(StandardClaimNames.NAME),
                jwt.getClaimAsString(StandardClaimNames.PREFERRED_USERNAME));
    }
}
