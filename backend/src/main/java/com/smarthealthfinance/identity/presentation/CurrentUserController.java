package com.smarthealthfinance.identity.presentation;

import com.smarthealthfinance.identity.application.GetCurrentUser;
import com.smarthealthfinance.identity.application.ProvisionCurrentUser;
import com.smarthealthfinance.identity.application.UpdateCurrentUserProfile;
import com.smarthealthfinance.identity.application.UserView;
import com.smarthealthfinance.identity.domain.DisplayName;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

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

	public record UpdateProfileRequest(@NotBlank @Size(max = DisplayName.MAX_LENGTH) String displayName) {
	}

	public record UserResponse(UUID id, String email, String displayName, String status, Instant createdAt) {

		static UserResponse from(UserView view) {
			return new UserResponse(view.id(), view.email(), view.displayName(), view.status().name(),
					view.createdAt());
		}
	}

	/** Resposta do provisionamento: o usuário + o id do seu Workspace pessoal. */
	public record ProvisionedUserResponse(UUID id, String email, String displayName, String status, Instant createdAt,
			UUID workspaceId) {

		static ProvisionedUserResponse from(ProvisionCurrentUser.Result result) {
			UserView view = result.user();
			return new ProvisionedUserResponse(view.id(), view.email(), view.displayName(), view.status().name(),
					view.createdAt(), result.workspaceId());
		}
	}
}
