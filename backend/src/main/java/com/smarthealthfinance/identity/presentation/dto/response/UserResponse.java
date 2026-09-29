package com.smarthealthfinance.identity.presentation.dto.response;

import com.smarthealthfinance.identity.application.dto.UserView;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String displayName, String status, Instant createdAt) {

	public static UserResponse from(UserView view) {
		return new UserResponse(view.id(), view.email(), view.displayName(), view.status().name(),
				view.createdAt());
	}
}
