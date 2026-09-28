package com.smarthealthfinance.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetCurrentUser {

    private final CurrentUserService currentUser;

    public GetCurrentUser(CurrentUserService currentUser) {
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public UserView execute() {
        return UserView.from(currentUser.requireActiveUser());
    }
}
