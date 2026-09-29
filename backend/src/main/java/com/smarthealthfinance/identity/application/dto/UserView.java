package com.smarthealthfinance.identity.application.dto;

import com.smarthealthfinance.identity.domain.enums.UserStatus;
import com.smarthealthfinance.identity.domain.model.User;

import java.time.Instant;
import java.util.UUID;

public record UserView(
        UUID id, String email, String displayName, UserStatus status, Instant createdAt
) {
    public static UserView from(User user) {
        return new UserView(
                user.id().value(), user.email().value(), user.displayName().value(), user.status(), user.createdAt()
        );
    }
}
