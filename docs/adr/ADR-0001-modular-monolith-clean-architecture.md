# ADR-0001 — Domain-Centric Modular Monolith com Clean Architecture

## Status

Accepted

## Context

O Smart Health Finance precisa de integridade financeira, isolamento por Workspace, evolução incremental e uma
equipe pequena (dupla). As specs aprovadas (05.1, 05.3, 05.13) definem a arquitetura, mas o código do backend
referencia esta decisão (`ArchitectureTest`) e ela precisava de registro formal.

## Decision

- Um único deployable Spring Boot organizado como **monólito modular orientado ao domínio**.
- Módulos por bounded context (`identity`, `accounts`, `transactions`, ...) sob `com.smarthealthfinance.<modulo>`,
  mais `shared` para contratos transversais.
- Dentro de cada módulo, camadas de **Clean Architecture**:
  `presentation → application → domain`; `infrastructure` implementa portas definidas pelas camadas internas.
- Regras garantidas por ArchUnit (`ArchitectureTest`):
  - `domain` não depende de Spring, JPA nem de outras camadas;
  - `application` não depende de `infrastructure` nem de `presentation`;
  - `presentation` não depende de `infrastructure`;
  - controllers só em `presentation`;
  - módulos livres de ciclos;
  - `domain` sem `float`/`double`.
- Os nomes de pacote seguem exatamente `domain`, `application`, `infrastructure` e `presentation`: as regras do
  ArchUnit dependem desses nomes.

## Alternatives

- **Microserviços desde o início:** rejeitado (specs 05.1): custo operacional, consistência distribuída e
  complexidade prematura para o MVP.
- **Monólito em camadas técnicas (controller/service/repository globais):** rejeitado; dilui os limites de
  domínio e dificulta extrair módulos no futuro.
- **Spring Modulith:** não adotado por ora; ArchUnit cobre as regras necessárias sem nova dependência.
  Pode ser reavaliado em ADR futuro.

## Consequences

- Positivas: transações locais para invariantes financeiras, deploy simples, limites verificados em CI,
  caminho aberto para extrair workers/módulos quando justificado.
- Negativas: disciplina de dependências depende dos testes de arquitetura; mapeamento extra entre domínio,
  entidades JPA e DTOs.

## Security / Privacy

Autorização contextual (incluindo Workspace) fica na camada Application, não em filtros HTTP.

## Financial Integrity

Regras financeiras vivem no domínio puro e determinístico, testável sem infraestrutura.
Dinheiro nunca usa ponto flutuante (regra ArchUnit).

## Operational Impact

Um artefato e uma imagem Docker. Workers assíncronos podem rodar como instâncias adicionais do mesmo artefato.

## Related Specs

- `specs/05-system-design/05.1-system-architecture.md`
- `specs/05-system-design/05.3-application-architecture.md`
- `specs/05-system-design/05.13-consolidated-system-design.md`

## Date

2026-09-28
