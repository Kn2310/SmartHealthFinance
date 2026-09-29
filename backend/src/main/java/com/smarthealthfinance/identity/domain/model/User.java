package com.smarthealthfinance.identity.domain.model;

import com.smarthealthfinance.identity.domain.enums.UserStatus;
import com.smarthealthfinance.identity.domain.exception.UserDisabledException;
import com.smarthealthfinance.identity.domain.valueobject.DisplayName;
import com.smarthealthfinance.identity.domain.valueobject.Email;
import com.smarthealthfinance.identity.domain.valueobject.ExternalIdentity;
import com.smarthealthfinance.identity.domain.valueobject.UserId;

import java.time.Instant;
import java.util.Objects;

public final class User {

    private final UserId id;
    private final ExternalIdentity externalIdentity;
    private Email email;
    private DisplayName displayName;
    private UserStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private User(
            UserId id,
            ExternalIdentity externalIdentity,
            Email email,
            DisplayName displayName,
            UserStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.externalIdentity = Objects.requireNonNull(externalIdentity, "externalIdentity");
        this.email = Objects.requireNonNull(email, "email");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static User provision(
            UserId id,
            ExternalIdentity externalIdentity,
            Email email,
            DisplayName displayName,
            Instant now
    ) {
        return new User(id, externalIdentity, email, displayName, UserStatus.ACTIVE, now, now, 0);
    }

    public static User restore(
            UserId id,
            ExternalIdentity externalIdentity,
            Email email,
            DisplayName displayName,
            UserStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return new User(id, externalIdentity, email, displayName, status, createdAt, updatedAt, version);
    }

    public boolean syncEmail(Email newEmail, Instant now) {
        ensureActive();

        if (email.equals(newEmail)) {
            return false;
        }

        email = newEmail;
        updatedAt = now;
        return true;
    }

    public void rename(DisplayName newName, Instant now) {
        ensureActive();

        if (displayName.equals(newName)) {
            return;
        }

        displayName = newName;
        updatedAt = now;
    }

    public void disable(Instant now) {
        if (status == UserStatus.DISABLED) {
            return;
        }
        status = UserStatus.DISABLED;
        updatedAt = now;
    }

    public void ensureActive() {
        if (status != UserStatus.ACTIVE) {
            throw new UserDisabledException(id);
        }
    }

    public UserId id() { return id; }
    public ExternalIdentity externalIdentity() { return externalIdentity; }
    public Email email() { return email; }
    public DisplayName displayName() { return displayName; }
    public UserStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
