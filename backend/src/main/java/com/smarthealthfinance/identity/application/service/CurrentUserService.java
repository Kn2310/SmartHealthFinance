package com.smarthealthfinance.identity.application.service;

import com.smarthealthfinance.identity.application.exception.UserNotProvisionedException;
import com.smarthealthfinance.identity.application.port.AuthenticatedIdentityProvider;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserService {

    private final AuthenticatedIdentityProvider identityProvider;
    private final UserRepository users;

    public CurrentUserService(AuthenticatedIdentityProvider identityProvider, UserRepository users) {
        this.identityProvider = identityProvider;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserId requireCurrentUserId() {
        return requireActiveUser().id();
    }

    public User requireActiveUser() {
        User user = users.findByExternalIdentity(identityProvider.current().externalIdentity())
                .orElseThrow(UserNotProvisionedException::new);

        user.ensureActive();
        return user;
    }
}
