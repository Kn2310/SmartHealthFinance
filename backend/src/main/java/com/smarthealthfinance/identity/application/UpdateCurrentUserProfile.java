package com.smarthealthfinance.identity.application;

import com.smarthealthfinance.identity.domain.DisplayName;
import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class UpdateCurrentUserProfile {

    private final CurrentUserService currentUser;
    private final UserRepository users;
    private final Clock clock;

    public UpdateCurrentUserProfile(CurrentUserService currentUser, UserRepository users, Clock clock) {
        this.currentUser = currentUser;
        this.users = users;
        this.clock = clock;
    }

    public record Command(String displayName) {}

    @Transactional
    public UserView execute(Command command) {
        User user = currentUser.requireActiveUser();
        user.rename(new DisplayName(command.displayName()), clock.instant());
        users.save(user);

        return UserView.from(user);
    }
}
