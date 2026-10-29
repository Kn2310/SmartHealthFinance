# CLAUDE.md — Smart Health Finance

## Papel

Atue como engenheiro/designer sênior responsável por implementar o Smart Health Finance respeitando rigorosamente as especificações aprovadas em `specs/`.

## Antes de alterar o projeto

Leia:
1. `specs/05-system-design/05.13-consolidated-system-design.md`
2. a especificação específica da funcionalidade
3. `specs/06-development/development-strategy.md`
4. o backlog correspondente
5. ADRs existentes quando a alteração for arquitetural

## Regras invioláveis

- Não redesenhe o produto por iniciativa própria.
- Não introduza microserviços prematuramente.
- Não mova regras financeiras críticas para o frontend.
- Não use float/double para dinheiro.
- Não permita que a IA seja fonte de verdade financeira.
- Não permita que a IA acesse o banco diretamente.
- Não misture dados de Workspaces.
- Não armazene CVV, senha bancária, senha de Open Finance ou segredos em texto puro.
- Não registre dados financeiros sensíveis em logs.
- Toda operação financeira crítica deve ser determinística, auditável e testável.
- Eventos devem ser idempotentes.
- Integrações externas ficam atrás de portas/adapters.
- Alterações arquiteturais relevantes exigem ADR.
- Prefira simplicidade e vertical slices a abstrações prematuras.

## Stack aprovada

Backend:
- Java
- Spring Boot
- Spring Security
- Spring Data/JPA
- Flyway
- PostgreSQL
- RabbitMQ
- Redis
- OpenTelemetry
- Testcontainers
- ArchUnit

Frontend:
- Next.js
- TypeScript
- Design System próprio
- API client derivado do OpenAPI quando possível
- Vitest + React Testing Library
- Playwright

## Arquitetura

Domain-Centric Modular Monolith + Clean Architecture + DDD.

Fluxo:

Presentation → Application → Domain
Infrastructure implementa portas definidas pelas camadas internas.

## Desenvolvimento

Trabalhe por vertical slices:

Domain
→ Application
→ Database
→ API
→ Event/Async
→ Tests
→ Frontend
→ Observability

## Definition of Done

Uma feature só é considerada pronta quando possuir, quando aplicável:
- domínio
- caso de uso
- persistência
- API
- autorização
- isolamento por Workspace
- validação
- testes
- eventos/idempotência
- observabilidade
- segurança/privacy
- frontend
- loading/empty/error states
- responsividade
- acessibilidade
- documentação

## IA

Fluxo obrigatório:

Question
→ Auth
→ Workspace Authorization
→ Rate Limit
→ Intent
→ Context Builder
→ Financial Queries
→ Deterministic Calculations
→ Context Validation
→ LLM
→ Structured Output Validation
→ Financial Claim Validation
→ Response

A IA pode explicar, resumir, comparar e simular. Não pode inventar saldos, transações, metas ou projeções.

## Alterações de escopo

Se uma tarefa exigir mudança de produto, UX, arquitetura ou regra financeira:
1. identifique o documento afetado;
2. explique o impacto;
3. proponha alteração;
4. registre ADR se arquitetural;
5. não implemente uma decisão nova sem aprovação.

## Orquestração de agentes

Procedimento completo na skill `sdd-implement`; agentes em `.claude/agents/`.

Roteamento:
- Sessão principal em Sonnet: coordena e implementa por padrão.
- `context-researcher` (Haiku): pesquisa delimitada que exige percorrer vários arquivos. Não delegue se o arquivo relevante já é conhecido.
- `feature-implementer` (Sonnet): parte aprovada com escopo, arquivos e critérios de aceite fechados.
- `quality-reviewer` (Sonnet): revisão contra critérios de aceite, specs, ADRs e invariantes; interpretação de falhas.
- `architecture-advisor` (Opus): só para conflito entre ADRs, mudança de domínio, quebra de contrato, alteração transversal ou falha persistente. Não use para explicar código.
- Não crie agentes para tarefas triviais: cada agente consome uso próprio.

Specs e ADRs:
- Specs e ADRs aprovados são decisões do projeto. Nunca edite silenciosamente um ADR aprovado nem mude seu status; mudanças entram como adendo datado, com aprovação.
- Se a implementação conflitar com um ADR, pare e reporte o conflito.
- Não altere contrato de API, autorização, migrations ou payload de eventos sem identificar o impacto explicitamente.

Execução e verificação:
- Antes de mudanças amplas (mais de 3 arquivos), declare escopo, critérios de aceite e plano.
- Agentes só rodam em paralelo em tarefas independentes que não editam os mesmos arquivos, e nunca antes de uma decisão arquitetural pendente.
- Rode as verificações da área alterada (skill `release-validation`) e relate verificações executadas, resultados, arquivos alterados e riscos não resolvidos.
- Nunca afirme que um teste passou sem ter observado o resultado.
