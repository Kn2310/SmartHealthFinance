# Runbook — Staging: backup, teste de restore, reset e seed

Referência: ADR-0044 §8, specs 05.11 (backups, restore tests) e 05.12 (staging sem dados reais).

## Backup

- **Diário** (`shf-backup.timer`, 06:00 UTC = 03:00 em Brasília): `pg_dump` em formato custom do banco da aplicação e
  do banco do Keycloak, em `/var/backups/shf/<data>-{app,keycloak}.dump` (root, 600), **7 dias**.
- **Snapshots automáticos do Lightsail**: o disco inteiro, os 7 últimos dias.
- Manual: `sudo shf-backup`. Estado do timer: `systemctl list-timers shf-backup.timer`; último resultado:
  `journalctl -u shf-backup --since -2d`.

Os dumps contêm só dados fictícios do seed. Mesmo assim, não os copie para fora do host.

## Teste de restore

```bash
sudo shf-backup && sudo shf-restore-test
```

O script restaura o dump mais recente da aplicação num database temporário (`shf_restore_test`), compara as
contagens (usuários, Workspaces, contas, transações, cartões, objetivos, migrations do Flyway) e a impressão digital
das transações (base de todos os saldos derivados) com o banco em uso e apaga o temporário.

- `CONFERE`: o restore funciona.
- `DIVERGE`: esperado se houve escrita entre o dump e o teste. Rode de novo logo após um `shf-backup`, sem uso.
- Repita a cada mudança relevante de schema ou da rotina de backup.

### Registro dos testes

| Data | SHA | Dump | Resultado | Quem |
|---|---|---|---|---|
| _(primeiro teste: depois do primeiro deploy e seed)_ | | | | |

### Restaurar de verdade (perda do banco)

1. `sudo docker compose -p shf-staging stop api web keycloak`
2. Recrie o database a partir do dump:
   `sudo docker compose -p shf-staging exec -T postgres pg_restore -U <DB_USERNAME> -d <DB_NAME> --clean --if-exists --no-owner < /var/backups/shf/<data>-app.dump`
   (o mesmo com `KC_DB_NAME` e o dump do Keycloak).
3. Redeploy do SHA atual (`staging-deploy.md`).

Perda do host: restaure o último snapshot do Lightsail numa nova instância, reanexe o IP estático e confira o
`known_hosts` do Environment (a chave do host muda se a instância for nova).

## Reset

O staging pode ser zerado a qualquer momento. **Se aparecer qualquer dado real, zere sem exceção** (05.11).

```bash
sudo shf-reset
```

Pede para digitar `zerar staging`. Faz um backup, apaga os volumes do PostgreSQL (aplicação e Keycloak) e do
RabbitMQ, sobe o ambiente (o realm é reimportado do arquivo versionado) e roda o seed. Preserva os certificados
(limites do Let's Encrypt) e a telemetria.

## Seed

```bash
sudo shf-seed
```

- Cria, se ausentes, os usuários sintéticos `SEED_MARINA_USERNAME` (dados completos) e `SEED_BRUNO_USERNAME` (sem
  dados, para conferir o isolamento entre Workspaces), com e-mail verificado.
- Liga o client `shf-staging-seed`, roda `infrastructure/staging/seed/seed.mjs` pela API (rede interna) e **desliga o
  client**, inclusive em falha. Se a mensagem "não consegui desligar o client de seed" aparecer, desligue-o no console
  do Keycloak (túnel SSH) imediatamente.
- Idempotente: se a usuária já tem contas, o seed não repete. Seed interrompido no meio → `shf-reset`.
- Conteúdo e diferenças em relação a `design/specs/09-dados-de-exemplo.md`: `infrastructure/staging/seed/README.md`.
