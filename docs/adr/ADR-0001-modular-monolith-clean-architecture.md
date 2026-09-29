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

### Subpacotes por camada (revisão de 2026-09-29)

Cada camada de um módulo é dividida em subpacotes por papel. É a estrutura padrão para os módulos novos. Um
subpacote só é criado quando tem conteúdo, sem pastas vazias "para o futuro".

```text
com.smarthealthfinance.<modulo>/
├── domain/
│   ├── model/          agregados e entidades de domínio (User, Workspace, WorkspaceMembership)
│   ├── valueobject/    objetos de valor imutáveis (UserId, Email, WorkspaceName, ...)
│   ├── enums/          enumerações do domínio (UserStatus, WorkspaceRole, ...)
│   ├── repository/     portas de persistência (interfaces)
│   └── exception/      violações de regra de domínio
├── application/
│   ├── usecase/        um caso de uso (command/query) por classe, com Command/Result aninhados
│   ├── service/        colaboradores compartilhados entre casos de uso (ex.: WorkspaceAccessGuard)
│   ├── dto/            views/DTOs de saída da Application
│   ├── port/           portas para capacidades externas (ex.: AuthenticatedIdentityProvider)
│   └── exception/      erros de aplicação (não encontrado, não provisionado, ...)
├── infrastructure/
│   ├── persistence/
│   │   ├── entity/     entidades JPA (@Entity/@Embeddable) e mapeamento de/para o domínio
│   │   ├── repository/ interfaces Spring Data
│   │   └── adapter/    implementações das portas de domain.repository
│   └── <capacidade>/   demais adapters (security, messaging, ...)
└── presentation/
    ├── controller/     @RestController
    ├── dto/
    │   ├── request/    corpos de requisição com Bean Validation
    │   └── response/   contratos de resposta da API
    └── handler/        @RestControllerAdvice do módulo
```

- "Entidade" de domínio (`domain.model`) e entidade JPA (`infrastructure.persistence.entity`) são coisas distintas.
  A JPA nunca vaza para o domínio.
- `shared` mantém sua própria organização por capacidade (`security`, `time`, `error`, `web`).
- Os testes espelham os pacotes do código de produção.
- Regras garantidas por ArchUnit:
  - `@RestController` só em `presentation.controller`;
  - `@RestControllerAdvice` de módulo só em `presentation.handler`;
  - `@Service` só em `application.usecase` ou `application.service`;
  - enums de domínio só em `domain.enums`;
  - exceções de módulo só em `domain.exception` ou `application.exception`;
  - `@Entity`/`@Embeddable` só em `infrastructure.persistence.entity`;
  - repositórios Spring Data só em `infrastructure.persistence.repository`.

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
- Subpacotes por camada: a navegação fica mais previsível e os módulos novos seguem um template. Em troca, membros
  usados entre subpacotes (mapeamentos `from`/`toDomain`, `View.from`, colaboradores de `application.service`)
  precisam ser `public`. O encapsulamento por pacote diminui, e o ArchUnit passa a ser a proteção dos limites entre
  camadas.

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

2026-09-28 (revisado em 2026-09-29: subpacotes por camada)
