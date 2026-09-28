package com.smarthealthfinance.identity.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.smarthealthfinance.identity.domain.ExternalIdentity;
import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.UserRepository;

/**
 * Fake com semântica próxima do adapter JPA: devolve cópias (sem aliasing) e incrementa version no save.
 */
final class InMemoryUserRepository implements UserRepository {

	private final Map<UserId, User> users = new LinkedHashMap<>();
	private User concurrentWinner;
	private int saveCount;

	/** Na próxima chamada de addIfAbsent, simula outra requisição inserindo este usuário antes. */
	void simulateConcurrentProvisioning(User winner) {
		this.concurrentWinner = winner;
	}

	void store(User user) {
		users.put(user.id(), copy(user, user.version()));
	}

	int size() {
		return users.size();
	}

	int saveCount() {
		return saveCount;
	}

	@Override
	public Optional<User> findById(UserId id) {
		return Optional.ofNullable(users.get(id)).map(user -> copy(user, user.version()));
	}

	@Override
	public Optional<User> findByExternalIdentity(ExternalIdentity externalIdentity) {
		return users.values()
			.stream()
			.filter(user -> user.externalIdentity().equals(externalIdentity))
			.findFirst()
			.map(user -> copy(user, user.version()));
	}

	@Override
	public boolean addIfAbsent(User user) {
		if (concurrentWinner != null) {
			store(concurrentWinner);
			concurrentWinner = null;
		}
		if (findByExternalIdentity(user.externalIdentity()).isPresent()) {
			return false;
		}
		store(user);
		return true;
	}

	@Override
	public void save(User user) {
		if (!users.containsKey(user.id())) {
			throw new IllegalStateException("save() só atualiza usuários existentes");
		}
		saveCount++;
		users.put(user.id(), copy(user, user.version() + 1));
	}

	private static User copy(User user, long version) {
		return User.restore(user.id(), user.externalIdentity(), user.email(), user.displayName(), user.status(),
				user.createdAt(), user.updatedAt(), version);
	}

}
