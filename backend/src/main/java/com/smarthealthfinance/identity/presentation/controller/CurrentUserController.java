package com.smarthealthfinance.identity.presentation.controller;

import com.smarthealthfinance.identity.application.usecase.GetCurrentUser;
import com.smarthealthfinance.identity.application.usecase.ProvisionCurrentUser;
import com.smarthealthfinance.identity.application.usecase.UpdateCurrentUserProfile;
import com.smarthealthfinance.identity.presentation.dto.request.UpdateProfileRequest;
import com.smarthealthfinance.identity.presentation.dto.response.ProvisionedUserResponse;
import com.smarthealthfinance.identity.presentation.dto.response.UserResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Users")
public class CurrentUserController {

	private final GetCurrentUser getCurrentUser;
	private final ProvisionCurrentUser provisionCurrentUser;
	private final UpdateCurrentUserProfile updateProfile;

    public CurrentUserController(GetCurrentUser getCurrentUser, ProvisionCurrentUser provisionCurrentUser, UpdateCurrentUserProfile updateProfile) {
        this.getCurrentUser = getCurrentUser;
        this.provisionCurrentUser = provisionCurrentUser;
        this.updateProfile = updateProfile;
    }

	@GetMapping
	public UserResponse get() {
		return UserResponse.from(getCurrentUser.execute());
	}

	@PostMapping
	@Operation(summary = "Provisiona o usuário autenticado e garante seu Workspace pessoal (idempotente)")
	@ApiResponse(responseCode = "201", description = "Usuário criado junto com o Workspace pessoal")
	@ApiResponse(responseCode = "200", description = "Usuário já existia; Workspace pessoal garantido")
	@ApiResponse(responseCode = "403", description = "USER_DISABLED",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "422", description = "IDENTITY_CLAIMS_INCOMPLETE",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ProvisionedUserResponse> provision() {
		ProvisionCurrentUser.Result result = provisionCurrentUser.execute();
		ProvisionedUserResponse body = ProvisionedUserResponse.from(result);
		return result.created()
				? ResponseEntity.created(URI.create("/api/v1/users/me")).body(body)
				: ResponseEntity.ok(body);
	}

	@PatchMapping
	public UserResponse update(@Valid @RequestBody UpdateProfileRequest request) {
		return UserResponse.from(updateProfile.execute(new UpdateCurrentUserProfile.Command(request.displayName())));
	}
}
