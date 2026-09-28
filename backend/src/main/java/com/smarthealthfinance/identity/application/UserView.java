package com.smarthealthfinance.identity.application;

import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserView(
        UUID id, String email, String displayName, UserStatus status, Instant createdAt
) {
    static UserView from(User user) {
        return new UserView(
                user.id().value(), user.email().value(), user.displayName().value(), user.status(), user.createdAt()
        );
    }
}
