package com.smarthealthfinance.identity.application;

import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.UserRepository;
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

    User requireActiveUser() {
        User user = users.findByExternalIdentity(identityProvider.current().externalIdentity())
                .orElseThrow(UserNotProvisionedException::new);

        user.ensureActive();
        return user;
    }
}
