---
name: backend-tests
description: Escreve e roda os testes do backend do Smart Health Finance (JUnit 5 + AssertJ, fakes em memória, ITs com Testcontainers e MockMvcTester, ArchUnit, suíte de isolamento entre Workspaces e contrato OpenAPI). Use ao testar domínio, casos de uso, persistência, API, consumidores de eventos ou regras de arquitetura no backend.
---

# Backend tests

Toda operação financeira crítica precisa ser **determinística, auditável e testável** (CLAUDE.md). Um teste instável é defeito, não algo a repetir (spec 05.10).

## Como rodar

| Comando (em `backend/`; no Windows, `mvnw.cmd`) | O que roda |
|---|---|
| `./mvnw -B -ntp test` | `*Test`: unitários + `ArchitectureTest` (surefire, sem Docker) |
| `./mvnw -B -ntp verify` | tudo acima + `*IT` (failsafe, Docker obrigatório) + contrato OpenAPI |
| `./mvnw -B -ntp test -Dtest=CreateAccountTest` | um teste unitário |
| `./mvnw -B -ntp verify -Dit.test=AccountApiIT -Dtest=skip -Dsurefire.failIfNoSpecifiedTests=false` | uma IT |
| `./mvnw -B -ntp verify -Dshf.openapi.update=true` | regrava `src/test/resources/openapi/openapi.json` (revise o diff) |

O sufixo decide a fase: **`*Test` nunca sobe Spring nem container; `*IT` sempre estende `IntegrationTest`.** O `verify` completo leva vários minutos. Antes de concluir, rode-o inteiro.

## Pirâmide por camada

| Camada | Classe | Ferramentas | Modelo no repo |
|---|---|---|---|
| Value object, agregado, serviço de domínio | `XxxTest` | JUnit 5, AssertJ, `@ParameterizedTest` | `AccountNameTest`, `AccountTest`, `InsightEngineTest` |
| Caso de uso | `XxxTest` | fakes em memória, `Clock.fixed` | `CreateAccountTest` + `AccountsTestContext` |
| Listener (filtro/retry) | `XxxEventListenerTest` | `Message` construída à mão + `JsonMapper` | `InsightsEventListenerTest` |
| Adapter de persistência | `JpaXxxRepositoryIT` / `JdbcXxxStoreIT` | Postgres real, `TransactionTemplate` | `JpaAccountRepositoryIT` |
| API | `XxxApiIT` | `MockMvcTester` + `jwt()` | `AccountApiIT` |
| Fluxo assíncrono ponta a ponta | `XxxWorkerIT` | RabbitMQ real, Awaitility | `InsightsWorkerIT` |
| Isolamento entre Workspaces | `isolation/WorkspaceIsolationIT` | parametrizado | acrescente casos, nunca uma suíte nova |
| Arquitetura | `architecture/ArchitectureTest` | ArchUnit | acrescente regras do módulo novo |
| Contrato e erros | `OpenApiContractIT`, `ErrorContractIT` | snapshot | regravar o snapshot quando a API mudar |

## Unitários de domínio

- Sem Spring e sem mocks: construa os objetos e verifique comportamento e invariantes.
- Erros de validação com `InvalidValueAssert.assertInvalidValue(() -> ..., "campo", "CODIGO")` (`shared/domain`).
- Dinheiro: compare `Money`/`BigDecimal` com `isEqualByComparingTo` ou `Money.of("10.00", BRL)`, nunca com `double`.
- Tempo: `Instant.parse("2026-09-27T12:00:00Z")` fixo. Nada de `Instant.now()`/`LocalDate.now()` em teste unitário.
- Transições: teste o retorno `boolean` ("mudou?") e a idempotência (repetir a operação retorna `false` e não altera `updatedAt`).

## Casos de uso com fakes (sem Mockito)

O padrão do repo é um **TestContext** por módulo, em `src/test/java/.../<modulo>/application/`:

```java
public final class XxxTestContext {
    public static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    public final InMemoryUserRepository users = new InMemoryUserRepository();
    public final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
    public final InMemoryXxxRepository xxxs = new InMemoryXxxRepository();
    public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    public final List<IntegrationEvent> outbox = new ArrayList<>();
    public final XxxEvents events = new XxxEvents(event -> { outbox.add(event); return UUID.randomUUID(); });
    public final User ana = activeUser("sub-ana");
    public final User bob = activeUser("sub-bob");
    public final Workspace anasWorkspace = personalWorkspace(ana);
    public final Workspace bobsWorkspace = personalWorkspace(bob);
    // XxxAccess real sobre CurrentUserService(this::currentIdentity, users) + WorkspaceAccessGuard(workspaces)
    public void actAs(User user) { ... }
}
```

- O `InMemoryXxxRepository` imita o adapter: **filtra por Workspace**, devolve **cópias** (via `restore`), incrementa `version` no `save`, `add` recusa id repetido e `save` recusa id inexistente.
- Cenários obrigatórios em todo caso de uso com Workspace:
  - caminho feliz (view retornada + estado persistido + `id().version() == 7`, porque é UUIDv7);
  - `ctx.actAs(ctx.bob)` no Workspace da Ana → `WorkspaceNotFoundException`, sem persistir nada;
  - recurso do Bob pedido no Workspace da Ana → `XxxNotFoundException` (o mesmo erro de inexistente);
  - entrada inválida → `InvalidValueException`, sem persistir (`ctx.xxxs.size()` inalterado);
  - evento na `ctx.outbox` só quando houve mudança, com payload contendo apenas ids;
  - operação repetida idempotente (sem segundo evento).

## Integração (`*IT extends IntegrationTest`)

`support/IntegrationTest` = `@SpringBootTest` + `@AutoConfigureMockMvc` (em Spring Boot 4: `org.springframework.boot.webmvc.test.autoconfigure`) + perfil `test` + `TestcontainersConfiguration` (Postgres 17, RabbitMQ 4 e Redis 7 como `@Bean @ServiceConnection`). **Todas as ITs compartilham um único contexto e os mesmos containers.**

- Não use `@DirtiesContext`, `@MockitoBean`, `@TestPropertySource` nem configuração por classe: cada uma cria um contexto novo e multiplica o tempo da suíte. Ajustes de tempo (debounce, intervalos de job) ficam em `src/test/resources/application-test.yml`.
- `@BeforeEach`: `IdentityTables.clean(jdbc)`. Ele espera a outbox e os consumidores ficarem ociosos (`awaitQuiescence`) e apaga na ordem das FKs. Tabela, consumidor ou job novo → atualize esse arquivo.
- Usuários via JWT de teste, sem IdP:
  ```java
  private static RequestPostProcessor ana() {
      return jwt().jwt(t -> t.subject("sub-ana").claim("iss", ISSUER).claim("email", "ana@example.com").claim("name", "Nome"));
  }
  ```
  O Workspace pessoal nasce com `mvc.post().uri("/api/v1/users/me").with(ana()).exchange()` e o id vem de `$.workspaceId`.
- Asserções com `MockMvcTester` (AssertJ):
  ```java
  assertThat(mvc.post().uri(ACCOUNTS, ws).with(ana()).contentType(MediaType.APPLICATION_JSON).content(json).exchange())
      .hasStatus(HttpStatus.CREATED)
      .bodyJson().hasPathSatisfying("$.code", c -> assertThat(c).asString().isEqualTo("VALIDATION_FAILED"));
  ```
- A API IT cobre: 201 + `Location`, defaults, cada `ErrorCode` do módulo (400/404/409/422), 401 sem token, 404 idêntico para recurso de outro Workspace, contagem no banco via `JdbcTemplate` e **logs sem dado sensível** (`@ExtendWith(OutputCaptureExtension.class)` + `assertThat(output).doesNotContain("Conta Secreta Pessoal")`).
- A persistence IT cobre: ida e volta domínio ↔ banco, consultas sempre filtradas por Workspace, `OptimisticLockingFailureException` com duas cópias da mesma versão, `DataIntegrityViolationException` para `check`/`unique` relevantes. Use `TransactionTemplate` quando precisar de transação explícita.
- Assíncrono: `await().atMost(Duration.ofSeconds(15)).untilAsserted(...)` (Awaitility), nunca `Thread.sleep`. Para DLQ, publique uma mensagem inválida com `RabbitTemplate` e verifique a fila `.dlq` com `RabbitAdmin`.

## Suítes de guarda (quebram de propósito)

- **`WorkspaceIsolationIT`**: todo endpoint novo sob `/api/v1/workspaces/{workspaceId}` precisa de um caso em `FOREIGN_WORKSPACE` (Bob no Workspace da Ana → 404 `WORKSPACE_NOT_FOUND`, sem dados e sem alterar tabelas). Se o endpoint recebe ids de outros recursos, acrescente também em `FOREIGN_REFERENCE`. O teste `everyWorkspaceScopedEndpointIsCovered` falha se faltar.
- **`ArchitectureTest`**: módulo novo → regras de direção (`nothingDependsOn<Modulo>`, `<modulo>OnlyReads...`) seguindo os exemplos de Analytics/Health/Insights. Nunca afrouxe uma regra existente sem ADR.
- **`OpenApiContractIT`**: diferença no `/v3/api-docs` falha o `verify`. Regrave o snapshot com `-Dshf.openapi.update=true` só quando a mudança for intencional, e atualize `frontend/src/lib/api/schema.d.ts` + `contract.ts` (ver skill `frontend-tests`).

## Checklist

- [ ] Unitários de domínio e caso de uso com Ana/Bob, `Clock.fixed` e fakes, sem Spring
- [ ] Persistence IT (isolamento, lock otimista, constraints) e API IT (status, erros, logs)
- [ ] Caso novo em `WorkspaceIsolationIT`; regras novas no `ArchitectureTest`
- [ ] `IdentityTables` atualizado para tabelas, consumidores e jobs novos
- [ ] Nenhum `sleep`, `now()` ou contexto Spring extra; `./mvnw -B -ntp verify` verde
