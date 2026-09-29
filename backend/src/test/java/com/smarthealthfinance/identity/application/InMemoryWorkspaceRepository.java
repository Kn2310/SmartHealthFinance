package com.smarthealthfinance.identity.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.Workspace;
import com.smarthealthfinance.identity.domain.WorkspaceId;
import com.smarthealthfinance.identity.domain.WorkspaceKind;
import com.smarthealthfinance.identity.domain.WorkspaceRepository;

/**
 * Fake com semântica próxima do adapter JPA: no máximo um Workspace PERSONAL por dono.
 * Workspace é imutável hoje, então não há risco de aliasing; copie no store quando surgirem mutações.
 */
final class InMemoryWorkspaceRepository implements WorkspaceRepository {

	private final Map<WorkspaceId, Workspace> workspaces = new LinkedHashMap<>();
	private Workspace concurrentWinner;
	private int insertAttempts;

	/** Na próxima chamada de addPersonalIfAbsent, simula outra requisição criando este Workspace antes. */
	void simulateConcurrentProvisioning(Workspace winner) {
		this.concurrentWinner = winner;
	}

	void store(Workspace workspace) {
		workspaces.put(workspace.id(), workspace);
	}

	int size() {
		return workspaces.size();
	}

	int insertAttempts() {
		return insertAttempts;
	}

	@Override
	public Optional<Workspace> findById(WorkspaceId id) {
		return Optional.ofNullable(workspaces.get(id));
	}

	@Override
	public Optional<Workspace> findPersonalByOwner(UserId ownerId) {
		return workspaces.values()
			.stream()
			.filter(workspace -> workspace.kind() == WorkspaceKind.PERSONAL && workspace.ownerId().equals(ownerId))
			.findFirst();
	}

	@Override
	public List<Workspace> findAllByMember(UserId userId) {
		return workspaces.values().stream().filter(workspace -> workspace.isMember(userId)).toList();
	}

	@Override
	public boolean addPersonalIfAbsent(Workspace workspace) {
		if (workspace.kind() != WorkspaceKind.PERSONAL) {
			throw new IllegalArgumentException("addPersonalIfAbsent aceita apenas Workspaces PERSONAL");
		}
		insertAttempts++;
		if (concurrentWinner != null) {
			store(concurrentWinner);
			concurrentWinner = null;
		}
		if (findPersonalByOwner(workspace.ownerId()).isPresent()) {
			return false;
		}
		store(workspace);
		return true;
	}

}
