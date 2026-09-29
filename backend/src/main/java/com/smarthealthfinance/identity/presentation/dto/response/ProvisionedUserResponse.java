package com.smarthealthfinance.identity.presentation.dto.response;

import com.smarthealthfinance.identity.application.dto.UserView;
import com.smarthealthfinance.identity.application.usecase.ProvisionCurrentUser;

import java.time.Instant;
import java.util.UUID;

/** Resposta do provisionamento: o usuário + o id do seu Workspace pessoal. */
public record ProvisionedUserResponse(UUID id, String email, String displayName, String status, Instant createdAt,
		UUID workspaceId) {

	public static ProvisionedUserResponse from(ProvisionCurrentUser.Result result) {
		UserView view = result.user();
		return new ProvisionedUserResponse(view.id(), view.email(), view.displayName(), view.status().name(),
				view.createdAt(), result.workspaceId());
	}
}
