package com.smarthealthfinance.identity.infrastructure.security;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smarthealthfinance.identity.application.AuthenticatedIdentity;
import com.smarthealthfinance.identity.domain.ExternalIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtAuthenticatedIdentityProviderTest {

	private final JwtAuthenticatedIdentityProvider provider = new JwtAuthenticatedIdentityProvider();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void mapsStandardClaims() {
		authenticate(jwt().claim("email", "ana@example.com")
			.claim("name", "Ana Silva")
			.claim("preferred_username", "ana"));

		AuthenticatedIdentity identity = provider.current();

		assertThat(identity.externalIdentity()).isEqualTo(new ExternalIdentity(ISSUER, "sub-ana"));
		assertThat(identity.email()).isEqualTo("ana@example.com");
		assertThat(identity.name()).isEqualTo("Ana Silva");
		assertThat(identity.preferredUsername()).isEqualTo("ana");
	}

	@Test
	void optionalClaimsMayBeAbsent() {
		authenticate(jwt());

		AuthenticatedIdentity identity = provider.current();

		assertThat(identity.email()).isNull();
		assertThat(identity.name()).isNull();
		assertThat(identity.preferredUsername()).isNull();
	}

	@Test
	void failsWithoutAuthentication() {
		assertThatThrownBy(provider::current).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
	}

	@Test
	void failsForNonJwtAuthentication() {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", "n/a"));

		assertThatThrownBy(provider::current).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
	}

	@Test
	void failsWithoutIssuer() {
		authenticate(jwt().claims(claims -> claims.remove(JwtClaimNames.ISS)));

		assertThatThrownBy(provider::current).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
	}

	@Test
	void failsWithBlankSubject() {
		authenticate(jwt().subject(" "));

		assertThatThrownBy(provider::current).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
	}

	private static Jwt.Builder jwt() {
		return Jwt.withTokenValue("token").header("alg", "none").issuer(ISSUER).subject("sub-ana");
	}

	private static void authenticate(Jwt.Builder jwt) {
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
	}

}
