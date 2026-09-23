# Smart Health Finance — Specification Pack

Este pacote é a fonte de verdade consolidada do projeto Smart Health Finance.

## Importante

A versão anterior do ZIP era um **resumo condensado** e não continha todas as especificações aprovadas durante a definição do produto. Este pacote corrige isso, reunindo o direcionamento completo aprovado nas fases 01–06, incluindo Produto, Marca, UX, Design System, System Design, Desenvolvimento, Segurança/Privacidade, IA, eventos, testes, DevOps e roadmap.

## Fonte de verdade

Ordem de prioridade:

1. `specs/05-system-design/05.13-consolidated-system-design.md`
2. Demais documentos de `specs/05-system-design/`
3. `specs/01-product/` a `specs/04-design-system/`
4. `specs/06-development/`
5. ADRs aprovados posteriormente, quando existentes

Em caso de conflito, não invente uma solução silenciosamente. Identifique o conflito e proponha um ADR.

## Estrutura

- `01-product/` — visão, problema, personas, JTBD, princípios, features, MVP e roadmap
- `02-brand/` — essência, personalidade, identidade visual e tokens
- `03-ux/` — arquitetura de informação, fluxos, UX e estados
- `04-design-system/` — tokens, componentes, navegação, componentes financeiros e inteligência
- `05-system-design/` — arquitetura completa 05.1–05.13
- `06-development/` — estratégia, backlog, ADR, ambiente local e Definition of Done

## Regra principal

O produto não é um chatbot financeiro. É uma camada de inteligência financeira que transforma dados em contexto, análise e decisões assistidas.

> Dados financeiros determinísticos são a verdade. A IA interpreta e contextualiza essa verdade.
