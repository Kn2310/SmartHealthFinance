package com.smarthealthfinance.identity;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Limpeza das tabelas do Identity e dos dados financeiros que dependem delas, respeitando as FKs
 * (memberships saem em cascata com workspaces).
 */
public final class IdentityTables {

	private IdentityTables() {
	}

	public static void clean(JdbcTemplate jdbc) {
		jdbc.update("delete from transaction_idempotency_keys");
		jdbc.update("delete from transactions");
		jdbc.update("delete from accounts");
		jdbc.update("delete from workspaces");
		jdbc.update("delete from users");
	}

}
