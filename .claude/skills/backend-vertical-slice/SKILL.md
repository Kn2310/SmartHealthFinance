---
name: backend-vertical-slice
description: Implementa uma fatia vertical no backend do Smart Health Finance (Java 25, Spring Boot 4.1, JPA, Flyway, outbox) seguindo a Clean Architecture e as convenções verificadas pelo ArchUnit. Use ao criar ou estender um módulo, agregado, caso de uso, endpoint REST, migration, evento de integração ou consumidor no backend.
---

# Backend vertical slice

Referência canônica: o módulo `accounts` (CRUD + eventos). Para um read model derivado por eventos, a referência é `insights`.
Antes de começar, siga o `CLAUDE.md`: leia a spec da feature, o backlog e as ADRs do módulo. **Decisão nova de produto, API ou arquitetura → proponha e registre uma ADR antes de implementar.**

## Ordem de trabalho

Domain → Application → Database → API → Event/Async → Tests → (Frontend) → Observability. Cada etapa compila e tem teste antes da próxima.

Skills de detalhe: `flyway-migration` (etapa 3), `async-events` (etapa 6), `backend-tests` (etapa 7) e, para a interface, `frontend-feature` e `frontend-tests`. Esta skill é o mapa geral; em caso de dúvida numa etapa, a skill específica vale.

## Layout do módulo (ArchUnit falha se for violado)

```
com.smarthealthfinance.<modulo>/
  domain/
    model/          agregados e entidades (POJO, sem Spring/JPA)
    valueobject/    records que se validam no construtor compacto
    enums/          TODO enum do domínio fica aqui
    exception/      exceções de regra (RuntimeException)
    repository/     interface do repositório (porta do agregado)
    service/        regras puras que não cabem num agregado
  application/
    usecase/        um @Service por caso de uso, método execute(...)
    service/        @Service auxiliares (Access, Events, ...)
    port/           portas de leitura/escrita que a infra implementa
    dto/            views (records) retornadas pelos casos de uso
    exception/      exceções de aplicação (ex.: NotFound)
  infrastructure/
    persistence/entity/      @Entity/@Embeddable JPA (separadas do domain.model)
    persistence/repository/  interfaces Spring Data
    persistence/adapter/     @Repository que implementa a porta do domínio
    messaging/               @RabbitListener e config de filas
  presentation/
    controller/     @RestController
    dto/request/  dto/response/
    handler/        @RestControllerAdvice do módulo
```

Regras de dependência: `domain` não importa Spring, `jakarta.persistence` nem outras camadas; `application` não importa `infrastructure`/`presentation`; `presentation` nunca importa `infrastructure`. Campos `float`/`double` no domínio são proibidos. Módulos não podem ter ciclos, e a direção entre eles é a do `ArchitectureTest` (ex.: um derivado como Insights lê apenas portas e modelos de domínio de outros módulos e conhece eventos só pelo nome). **Módulo novo → acrescente as regras de direção dele em `src/test/java/.../architecture/ArchitectureTest.java`.**

## 1. Domain

- Agregado: construtor privado + `static create(...)` (novo, version 0) e `static restore(...)` (reidratação). Campos imutáveis `final`; `Objects.requireNonNull` em tudo que é obrigatório.
- Sempre carregue `WorkspaceId workspaceId` (de `identity.domain.valueobject`).
- Id: `record XxxId(UUID value)` com `generate(Instant now)` via `UuidV7.generate(now)`.
- Value objects validam e normalizam (`strip`) no construtor e lançam `InvalidValueException(campo, CODIGO)` (`REQUIRED`, `TOO_LONG`, `INVALID`...). Expõem `MAX_LENGTH` para reuso no request.
- Transições retornam `boolean` ("mudou?") para o caso de uso decidir se grava evento. Operações repetidas são idempotentes (arquivar o que já está arquivado retorna `false`).
- Tempo sempre recebido como `Instant now`; o domínio nunca chama `Instant.now()`.
- **Dinheiro:** `shared.domain.Money` (BigDecimal escala 4 + `Currency`). Nunca `double`, nunca arredondamento implícito.
- Porta do repositório no domínio: toda consulta recebe `WorkspaceId` (`findById(WorkspaceId, XxxId)`); `add` para inserir e `save` para atualizar.

## 2. Application

```java
@Service
public class CreateXxx {
    private static final Logger log = LoggerFactory.getLogger(CreateXxx.class);
    // dependências via construtor: XxxAccess, XxxRepository, Clock, XxxEvents

    public record Command(String name, ...) {}   // tipos primitivos/String vindos da API

    @Transactional
    public XxxView execute(UUID workspaceId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Instant now = clock.instant();
        Xxx xxx = Xxx.create(XxxId.generate(now), workspace, new XxxName(command.name()), ..., now);
        repository.add(xxx);
        events.changed(xxx);                          // mesma transação (outbox)
        log.info("Xxx created xxxId={} workspaceId={}", xxx.id(), workspace);
        return XxxView.from(xxx);
    }
}
```

- **Autorização primeiro.** `XxxAccess` (`application/service`) chama `WorkspaceAccessGuard.requireMember(currentUser.requireCurrentUserId(), id)` e busca o recurso **dentro** do Workspace autorizado. Inexistente e de outro Workspace lançam a mesma `XxxNotFoundException` (regra do 404, ADR-0003/0004).
- Leituras: `@Transactional(readOnly = true)`. Escritas: `@Transactional`.
- Injete `java.time.Clock` (bean em `shared.infrastructure.time`); nunca `Instant.now()`.
- Logs: só ids e códigos. **Nunca** valores, descrições, nomes, e-mails ou payloads financeiros.
- Retorne um `XxxView` (record em `application/dto`) com `static from(Xxx)`; o controller não vê o agregado.
- Criação via API com efeito financeiro é idempotente por `Idempotency-Key`: siga `transactions` (`TransactionIdempotencyStore`: claim atômico + hash do request na mesma transação; mesma chave com corpo diferente → `IdempotencyKeyReusedException` → 422 `IDEMPOTENCY_KEY_REUSED`).

## 3. Database (Flyway)

- Arquivo `backend/src/main/resources/db/migration/V<N>__<verbo>_<objeto>.sql`, com N = último + 1. **Nunca edite uma migration já mergeada**; crie outra.
- Comece com um comentário citando spec/ADR. Padrões de `V4__create_accounts.sql`:
  - `id uuid primary key`, `workspace_id uuid not null` + FK para `workspaces(id)` **sem cascade**.
  - `created_at`/`updated_at timestamptz not null`, `version bigint not null default 0`.
  - Enums como `varchar(20)` + `check (col in (...))` espelhando o enum Java.
  - Dinheiro: `numeric(19,4)` + `currency varchar(3)`.
  - Índice começando por `workspace_id` para cada consulta do repositório.
- Tabela nova → acrescente o `delete from <tabela>` em `IdentityTables.clean` (test), na ordem das FKs.

## 4. Infrastructure (persistência)

- `XxxJpaEntity` em `persistence/entity`: `@Entity @Table`, construtor `protected` vazio, `static from(Xxx)` e `toDomain()` (via `Xxx.restore`). `@Version private long version;` usando **`jakarta.persistence.Version`** (o Spring Data rejeita `org.springframework.data.annotation.Version` em JPA). Colunas imutáveis com `updatable = false`; enums com `@Enumerated(EnumType.STRING)`.
- `XxxJpaRepository extends JpaRepository<XxxJpaEntity, UUID>` em `persistence/repository`, com métodos derivados que sempre incluem `WorkspaceId` (`findByIdAndWorkspaceId`, `findAllByWorkspaceIdOrderByCreatedAtAscIdAsc`).
- `JpaXxxRepository` (`@Repository`, em `persistence/adapter`) implementa a porta do domínio e converte entity ↔ domínio.
- Com id atribuído e `@Version` primitivo, o Spring Data trata a entidade como "não nova" e `save()` faz `merge()`. Isso é intencional: o `version` vindo do domínio faz o merge falhar com `OptimisticLockingFailureException` (→ 409 `CONFLICT` no handler global). Mantenha o padrão.
- Leituras agregadas/derivadas (read models) podem usar `JdbcTemplate` num adapter `Jdbc*Store` implementando uma porta de `application/port`, como em `insights`.

## 5. API (presentation)

- Rota sob `/api/v1/workspaces/{workspaceId}/<recursos>`, `@Tag(name = "...")`, um `@Operation(summary)` em português e `@ApiResponse` para cada código de erro (com `@Content(schema = @Schema(implementation = ApiError.class))`).
- Request: `record` com Bean Validation (`@NotBlank`, `@Size(max = XxxName.MAX_LENGTH)`); validação de regra fica no domínio. Use `@Valid @RequestBody`.
- Response: `record XxxResponse` com `static from(XxxView)`; enums viram `name()`.
- Dinheiro na API: `shared.presentation.money.MoneyDto` (`{"amount": "123.45", "currency": "BRL"}`, amount **string**).
- POST de criação: `ResponseEntity.created(URI)` + corpo. Ações de estado: `POST /{id}/<acao>` (idempotentes).
- Erros: códigos estáveis em `shared.presentation.error.ErrorCode` (acrescente os do módulo, com comentário citando a ADR). O `XxxExceptionHandler` em `presentation/handler` é `@RestControllerAdvice @Order(Ordered.HIGHEST_PRECEDENCE)` e responde `ResponseEntity.status(code.status()).body(ApiErrors.of(code))`.
- **Contrato OpenAPI (ADR-0010):** toda mudança de API exige regravar o snapshot e revisar o diff:
  `./mvnw verify -Dshf.openapi.update=true` (atualiza `src/test/resources/openapi/openapi.json`); depois atualize o `schema.d.ts` do frontend se aplicável.

## 6. Eventos e assíncrono

- **Publicar:** `XxxEvents` (`application/service`) grava um `IntegrationEvent` na `EventOutbox` **na mesma transação** do fato, e só quando algo mudou. `eventType` = `recurso.acao` (ex.: `account.changed`), `eventVersion` = 1. O payload carrega **apenas ids, códigos e contagens**, nunca valores ou textos; o consumidor relê a fonte.
- **Consumir:** listener em `infrastructure/messaging` (`@RabbitListener` + config da fila no módulo) valida tipo/versão/workspace do `EventEnvelope` e rejeita o inesperado com `AmqpRejectAndDontRequeueException`; propaga `correlationId` no MDC. O caso de uso chama `ProcessedEvents.markProcessed(CONSUMER, eventId)` primeiro, na mesma `@Transactional` do efeito, e retorna sem efeito se já processado. Modelo: `insights/.../InsightsEventListener` + `MarkInsightsDirty`.
- JSON no backend usa Jackson 3 (`tools.jackson.databind.json.JsonMapper`), não `com.fasterxml`.
- Novo consumidor ou evento → atualize `IdentityTables.awaitQuiescence` (test) para a suíte esperar por ele.

## 7. Testes (obrigatórios)

| Camada | Tipo | Modelo |
|---|---|---|
| Value objects / agregado | unitário JUnit 5 + AssertJ | `AccountNameTest`, `AccountTest` |
| Casos de uso | unitário com fakes em memória (sem Spring) | `CreateAccountTest` + `AccountsTestContext` + `InMemoryAccountRepository` |
| Adapter de persistência | `*IT extends IntegrationTest` (Postgres real) | `JpaAccountRepositoryIT` (isolamento, optimistic lock, constraints) |
| API | `*IT extends IntegrationTest` com `MockMvcTester` + `jwt()` | `AccountApiIT` |
| Isolamento | caso novo em `isolation/WorkspaceIsolationIT` | **obrigatório** para todo endpoint sob `/workspaces/{id}`; a suíte falha sem ele |

- `XxxTestContext` monta Ana e Bob com Workspaces próprios, `Clock.fixed`, a outbox como lista e `actAs(user)`. O fake do repositório imita o adapter: filtra por Workspace, devolve cópias e incrementa a version no `save`.
- Teste explicitamente: membro de outro Workspace → `WorkspaceNotFoundException`; recurso de outro Workspace → `XxxNotFoundException`; entrada inválida não persiste; evento gravado só quando houve mudança; logs sem dados sensíveis (`OutputCaptureExtension`).
- `IntegrationTest` já sobe Postgres/RabbitMQ/Redis via Testcontainers (`@ServiceConnection` em `TestcontainersConfiguration`) com contexto compartilhado. **Não** adicione configuração por classe nem `@DirtiesContext`. Em Spring Boot 4, `@AutoConfigureMockMvc` fica em `org.springframework.boot.webmvc.test.autoconfigure`.
- Limpe com `IdentityTables.clean(jdbc)` no `@BeforeEach`.

## 8. Observabilidade

- Log `info` por comando bem-sucedido (ids apenas). Contadores Micrometer (`MeterRegistry`) com prefixo `shf.<modulo>.` para trabalho assíncrono/jobs. O tracing (OpenTelemetry) e o `correlationId` já vêm da infraestrutura compartilhada; não crie spans manuais sem necessidade.

## Checklist final

- [ ] `./mvnw verify` verde (ArchUnit, unitários, ITs, contrato OpenAPI)
- [ ] Todo acesso filtrado por `WorkspaceId`; 404 indistinguível para recurso de outro Workspace
- [ ] Caso novo em `WorkspaceIsolationIT`; tabela nova em `IdentityTables`
- [ ] Nenhum `float`/`double` em dinheiro; `Money`/`numeric(19,4)`/`MoneyDto`
- [ ] Eventos via outbox com payload só de ids; consumidores idempotentes via `ProcessedEvents`
- [ ] Nada sensível em logs
- [ ] Snapshot OpenAPI atualizado e revisado; ADR criada ou atualizada quando houve decisão nova
