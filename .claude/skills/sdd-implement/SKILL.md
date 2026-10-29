---
name: sdd-implement
description: Conduz uma feature ou parte de milestone do Smart Health Finance pelo fluxo Spec-Driven Development (spec/ADR aprovados → plano → implementação em vertical slice → verificação → revisão) e decide quando delegar a context-researcher, feature-implementer, quality-reviewer ou architecture-advisor. Use ao começar uma parte do backlog ou uma feature de complexidade média ou alta; para tarefas pequenas, siga direto sem o pipeline completo.
---

# SDD implement

Fluxo da sessão principal (Sonnet) para entregar uma parte aprovada. A política de delegação está em `CLAUDE.md` § Orquestração de agentes; esta skill é o procedimento.

## 0. Dimensione

| Tamanho | Exemplo | Pipeline |
|---|---|---|
| Pequena | ajuste em 1–3 arquivos conhecidos, correção de teste, texto | Faça direto. Sem subagents. |
| Média | um caso de uso + endpoint + tela, já coberto por ADR | Passos 1–5; pesquisa só se o contexto estiver espalhado. |
| Alta / arquitetural | módulo novo, evento novo, mudança de contrato, conflito de ADR | Passo 2 com `architecture-advisor` **antes** de qualquer código. |

Delegar custa uso próprio de cada agente: se você já conhece os arquivos, não delegue.

## 1. Contexto

- Leia a entrada do backlog (`specs/06-development/backlog.md`), a spec da área e os ADRs citados (procure adendos com `Adendo` em `docs/adr/`).
- Contexto espalhado por vários módulos → `context-researcher` (Haiku) com uma pergunta delimitada. Peça evidências `arquivo:linha`.

## 2. Decisões

- Toda decisão de produto, UX, arquitetura ou regra financeira que a spec/ADR não responde é do usuário: pergunte com opções e recomendação. Não implemente decisão nova sem aprovação (CLAUDE.md § Alterações de escopo).
- Conflito entre ADRs, mudança de domínio, quebra de contrato ou alteração transversal → `architecture-advisor` (Opus). Leve o rascunho de ADR/adendo ao usuário; ADR novo usa `specs/06-development/adr-template.md`. Use a skill `adr-review` para checar o rascunho.

## 3. Plano

Antes de editar mais de 3 arquivos, escreva o plano: escopo, critérios de aceite, arquivos e alteração prevista em cada um, testes, impacto em contrato/migration/evento. Siga a ordem da vertical slice: Domain → Application → Database → API → Event → Tests → Frontend → Observability.

## 4. Implementação

- Faça você mesmo quando o contexto já está na sessão. Delegue ao `feature-implementer` uma parte com escopo, arquivos e critérios fechados — passe no prompt a spec, os ADRs, o plano e a skill técnica a carregar.
- Paralelismo só entre partes independentes que **não tocam os mesmos arquivos** (ex.: backend de um módulo e uma tela já com contrato fixado). Nunca implemente em paralelo com uma decisão arquitetural pendente.

## 5. Verificação e revisão

1. Rode as verificações determinísticas da área tocada (skill `release-validation`, seção "Por área") e leia a saída.
2. Para mudança média ou alta, peça ao `quality-reviewer` uma revisão com os critérios de aceite. Achado bloqueante → corrija e verifique de novo; falha que persiste → `architecture-advisor`.
3. Relate: arquivos alterados, verificações com resultado observado, pendências e riscos. Commit/push só com confirmação do usuário.
