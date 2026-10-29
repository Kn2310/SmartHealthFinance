# Agent Orchestration

Como o Claude Code é usado no desenvolvimento do Smart Health Finance. A política vigente está em `CLAUDE.md` § Orquestração de agentes; o procedimento, na skill `sdd-implement`.

## Componentes

| Componente | Modelo | Papel | Edita? |
|---|---|---|---|
| Sessão principal | Sonnet (`.claude/settings.local.json`, por máquina) | Coordena, decide quando delegar, implementa por padrão | Sim |
| `context-researcher` | Haiku | Pesquisa delimitada com evidências `arquivo:linha` | Não |
| `feature-implementer` | Sonnet | Executa parte aprovada com escopo fechado | Sim |
| `quality-reviewer` | Sonnet | Revisa contra critérios, specs, ADRs e invariantes | Não |
| `architecture-advisor` | Opus | Conflito de ADR, domínio, contrato, falha persistente | Não |

Skills do projeto (`.claude/skills/`):
- Procedimento: `sdd-implement`, `adr-review`, `release-validation`.
- Técnicas: `backend-vertical-slice`, `flyway-migration`, `async-events`, `backend-tests`, `frontend-feature`, `frontend-tests`.

Verificação determinística (fonte de verdade sobre "pronto"): `./mvnw verify`, `pnpm lint|typecheck|test|build`, E2E — ver `release-validation`.

## Implantação

1. **Auditoria** — concluída em 2026-10-29: não havia agents, hooks nem settings do projeto; 6 skills técnicas existentes foram mantidas.
2. **Configuração mínima** — concluída em 2026-10-29 (branch `chore/agent-orchestration`): 4 agents, 3 skills de procedimento, política no `CLAUDE.md`, Sonnet como padrão local.
3. **Piloto controlado** — pendente.
4. **Automação gradual** — só após o piloto.

## Piloto

Rodar três tarefas reais pelo fluxo novo e registrar abaixo.

| Tipo | Candidata | Agentes esperados |
|---|---|---|
| Pequena | ajuste pontual de 1–3 arquivos (ex.: correção de teste ou texto) | nenhum |
| Média | uma parte de implementação da Sessão 7.1 (M9 Forecast) já coberta pelo ADR-0030 | `feature-implementer`, `quality-reviewer` |
| Arquitetural | M9 Parte B — estratégia de geração do forecast (encadeado ADR-0018/0022 × sob demanda × híbrido) | `context-researcher`, `architecture-advisor` |

Métricas por tarefa:

| Tarefa | Data | Modelos/agentes usados | Uso (tokens ou % do limite) | Intervenções manuais | Retrabalho (ciclos de correção) | Falhas de verificação | Qualidade (achados na revisão) | Observações |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

Comparar com as sessões anteriores (M5–M8, sessão única) e decidir: manter, ajustar descrições dos agents ou remover algum.

## Automação gradual (após o piloto)

Candidatos, cada um só se o piloto mostrar ganho:
- hooks de validação (ex.: lembrar `release-validation` antes de commit);
- delegação automática mais agressiva (ajustando as `description` dos agents);
- agentes adicionais por especialidade, só com necessidade demonstrada.

Sempre com aprovação humana: mudança de ADR ou de seu status, contratos de API/evento, migrations, autorização e decisões de produto.
