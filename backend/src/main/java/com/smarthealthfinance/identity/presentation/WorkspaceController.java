package com.smarthealthfinance.identity.presentation;

import com.smarthealthfinance.identity.application.GetWorkspace;
import com.smarthealthfinance.identity.application.ListCurrentUserWorkspaces;
import com.smarthealthfinance.identity.application.WorkspaceView;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces")
@Tag(name = "Workspaces")
public class WorkspaceController {

    private final ListCurrentUserWorkspaces listWorkspaces;
    private final GetWorkspace getWorkspace;

    public WorkspaceController(ListCurrentUserWorkspaces listWorkspaces, GetWorkspace getWorkspace) {
        this.listWorkspaces = listWorkspaces;
        this.getWorkspace = getWorkspace;
    }

    @GetMapping
    @Operation(summary = "Lista os Workspaces em que o usuário autenticado é membro")
    @ApiResponse(responseCode = "200", description = "Workspaces do usuário")
    @ApiResponse(responseCode = "404", description = "USER_NOT_PROVISIONED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public WorkspaceListResponse list() {
        return new WorkspaceListResponse(listWorkspaces.execute().stream().map(WorkspaceResponse::from).toList());
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "Consulta um Workspace do qual o usuário autenticado é membro")
    @ApiResponse(responseCode = "200", description = "Workspace encontrado")
    @ApiResponse(responseCode = "404",
            description = "WORKSPACE_NOT_FOUND — inexistente ou sem acesso (indistinguíveis de propósito)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public WorkspaceResponse get(@PathVariable UUID workspaceId) {
        return WorkspaceResponse.from(getWorkspace.execute(workspaceId));
    }

    /** Envelope para permitir paginação futura sem quebrar o contrato (specs 05.6). */
    public record WorkspaceListResponse(List<WorkspaceResponse> items) {
    }

    public record WorkspaceResponse(UUID id, String name, String kind, String baseCurrency, String role,
            Instant createdAt) {

        static WorkspaceResponse from(WorkspaceView view) {
            return new WorkspaceResponse(view.id(), view.name(), view.kind().name(), view.baseCurrency(),
                    view.role().name(), view.createdAt());
        }
    }
}
