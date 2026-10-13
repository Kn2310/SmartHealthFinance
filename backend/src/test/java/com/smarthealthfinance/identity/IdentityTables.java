package com.smarthealthfinance.identity;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * Limpeza das tabelas do Identity e dos dados financeiros que dependem delas, respeitando as FKs
 * (memberships saem em cascata com workspaces).
 */
public final class IdentityTables {

	private IdentityTables() {
	}

	public static void clean(JdbcTemplate jdbc) {
		awaitQuiescence(jdbc);
		jdbc.update("delete from analytics_daily_flows");
		jdbc.update("delete from analytics_projections");
		jdbc.update("delete from imported_transaction_keys");
		jdbc.update("delete from import_records");
		jdbc.update("delete from import_batches");
		jdbc.update("delete from outbox_events");
		jdbc.update("delete from processed_events");
		jdbc.update("delete from transaction_idempotency_keys");
		jdbc.update("delete from transactions");
		jdbc.update("delete from category_rules");
		jdbc.update("delete from accounts");
		jdbc.update("delete from workspaces");
		jdbc.update("delete from users");
	}


	/**
	 * Espera a outbox esvaziar e o consumidor do Analytics (ADR-0014) processar os eventos dos testes anteriores, para
	 * que nenhum trabalho assíncrono grave em tabelas que estão sendo limpas (FK) ou mude um snapshot. Evento que não
	 * vai ser processado (ex.: na DLQ) não trava a suíte: depois do prazo a limpeza segue.
	 */
	public static void awaitQuiescence(JdbcTemplate jdbc) {
		long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		while (System.nanoTime() < deadline) {
			Integer busy = jdbc.queryForObject("""
					select count(*) from outbox_events o
					where o.published_at is null
					   or ((o.event_type like 'transaction.%' or o.event_type = 'import.completed')
					       and not exists (select 1 from processed_events p
					                       where p.consumer = 'analytics.projection' and p.event_id = o.id))
					""", Integer.class);
			if (busy == null || busy == 0) {
				return;
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

}
