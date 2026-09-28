package com.smarthealthfinance.identity.presentation;

import com.smarthealthfinance.identity.application.GetCurrentUser;
import com.smarthealthfinance.identity.application.ProvisionCurrentUser;
import com.smarthealthfinance.identity.application.UpdateCurrentUserProfile;
import com.smarthealthfinance.identity.application.UserView;
import com.smarthealthfinance.identity.domain.DisplayName;
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
    public ResponseEntity<UserResponse> provision() {
		ProvisionCurrentUser.Result result = provisionCurrentUser.execute();
		UserResponse body = UserResponse.from(result.user());
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
}
