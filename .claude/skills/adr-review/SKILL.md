---
name: adr-review
description: Revisa um ADR novo, um adendo ou um rascunho de decisão do Smart Health Finance contra o template, as specs, os ADRs existentes e os invariantes financeiros, apontando conflitos e impactos. Use ao escrever ou revisar um ADR/adendo, ou quando uma implementação parecer conflitar com uma decisão registrada.
---

# ADR review

ADRs ficam em `docs/adr/ADR-NNNN-<slug>.md`, numeração sequencial; template em `specs/06-development/adr-template.md`. Decisões que alteram um ADR anterior entram como **Adendo (AAAA-MM-DD, ADR-NNNN)** no ADR afetado, não como reescrita.

## Regras

- ADR aprovado (`Accepted`) não é editado silenciosamente: só adendos datados, com referência ao ADR que os motivou.
- Status só muda com aprovação explícita do usuário (`Proposed` → `Accepted` / `Rejected`; `Superseded` aponta o substituto).
- Mudança arquitetural, evento novo, mudança de payload, contrato de API, autorização ou migration com perda de dado exige ADR ou adendo.

## Checklist

1. **Template completo**: Status, Context, Decision, Alternatives, Consequences, Security / Privacy, Financial Integrity, Operational Impact, Related Specs, Date.
2. **Conflitos**: procure no `docs/adr/` (inclusive adendos) e em `specs/05-system-design/` decisões que a nova contradiz ou duplica. Liste cada conflito com `arquivo:seção` e diga se o novo ADR precisa de adendo nos anteriores.
3. **Integridade financeira**: `Money`/`numeric(19,4)`, cálculo determinístico no backend, reconciliação, saldo derivado, idempotência e outbox preservados.
4. **Workspace e segurança**: isolamento por Workspace, autorização (regra do 404), LGPD (spec 05.12), nada sensível em log.
5. **IA**: se tocar o Copilot, respeita o fluxo obrigatório do `CLAUDE.md` e a IA não vira fonte de verdade.
6. **Contratos e dados**: impacto em `openapi.json`/`schema.d.ts`, eventos, migrations e backfill descrito, com plano de rebuild/runbook quando houver read model.
7. **Operação**: observabilidade (métricas, logs sem dado sensível), runbook em `docs/runbooks/` quando houver DLQ/rebuild.
8. **Backlog e specs**: entradas de `specs/06-development/backlog.md` e specs afetadas apontam o ADR.

## Saída

Lista de achados (bloqueante / importante / menor) com `arquivo:seção`, conflitos encontrados, adendos necessários em ADRs anteriores e perguntas para o usuário. Escalar ao `architecture-advisor` quando houver conflito real entre decisões aceitas.
