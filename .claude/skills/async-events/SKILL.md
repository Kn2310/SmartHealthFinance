---
name: async-events
description: Publica e consome eventos de integração no Smart Health Finance (Transactional Outbox → RabbitMQ topic shf.events → filas por módulo com DLQ, inbox ProcessedEvents para idempotência, retry só de falhas transitórias, debounce + job). Use ao criar um evento novo, um consumidor, uma fila ou um read model atualizado de forma assíncrona.
---

# Async events

Spec 05.7, ADR-0009 §14 (outbox), ADR-0014 (Analytics), ADR-0018 (Health) e ADR-0022 (Insights). Entrega **at-least-once**: todo consumidor é idempotente. Evento novo, mudança de payload ou consumidor novo exige ADR (ou adendo).

## Fluxo

```
Caso de uso (@Transactional)
  ├─ muda o agregado
  └─ XxxEvents → EventOutbox.append(IntegrationEvent)        ← mesma transação
OutboxRelay (@Scheduled, FOR UPDATE SKIP LOCKED, publisher confirms)
  └─ exchange topic "shf.events", routing key = eventType
Fila do consumidor "shf.<modulo>.<proposito>" (+ ".dlq")
  └─ @RabbitListener → valida envelope → caso de uso @Transactional
        ├─ ProcessedEvents.markProcessed(CONSUMER, eventId)  ← false = já processado, sai sem efeito
        └─ efeito (ou só marca "dirty" para um job com debounce)
```

## Publicar um evento

- Classe `XxxEvents` em `<modulo>/application/service` (`@Service`) com constantes `CHANGED = "recurso.acao"` e `VERSION = 1`. Modelo: `accounts/application/service/AccountEvents.java`.
- `new IntegrationEvent(eventType, eventVersion, workspaceId, aggregateType, aggregateId, causationId, payload)`:
  - `eventType` precisa casar com `[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+` (ex.: `account.changed`, `health.snapshot-updated`);
  - `workspaceId` só é nulo em evento de sistema;
  - **payload só com ids, códigos e contagens.** Nunca valores, descrições, nomes ou dados pessoais: o consumidor relê a fonte da verdade.
- Grave **apenas quando algo mudou** (use o `boolean` retornado pelo domínio) e **dentro da mesma `@Transactional`** do fato. Não publique direto no `RabbitTemplate` a partir de caso de uso.
- Mudança incompatível de payload → `eventVersion` 2 e um período publicando as duas versões; os consumidores rejeitam versões que não conhecem.
- O envelope (`eventId`, `occurredAt`, `correlationId`, `traceId`...) é montado pela infraestrutura (`JdbcEventOutbox`/`EventEnvelope`). Não o replique.

## Consumir um evento

Arquivos em `<modulo>/infrastructure/messaging/` (modelo completo: `insights/infrastructure/messaging/`):

1. **`XxxMessagingConfig`** (`@Configuration(proxyBeanMethods = false)`):
   - `QUEUE = "shf.<modulo>.<proposito>"`, `DEAD_LETTER_QUEUE = QUEUE + ".dlq"`, `CONTAINER_FACTORY = "<modulo>ListenerContainerFactory"`;
   - os nomes dos eventos de outros módulos como **strings** (os módulos se conhecem só pelo nome do evento; o ArchUnit proíbe importar classes deles);
   - `Declarables`: fila durável com `deadLetterExchange("")` + `deadLetterRoutingKey(DEAD_LETTER_QUEUE)`, a DLQ durável e um binding no `TopicExchange eventsExchange` por eventType;
   - container factory própria: `configurer.configure(factory, connectionFactory)`, `factory.setDefaultRequeueRejected(false)` e `setAdviceChain(retryInterceptor(...))`;
   - retry **stateless** (a transação começa dentro do callback, no caso de uso) via `RetryInterceptorBuilder.stateless().configureRetryPolicy(p -> p.maxRetries(..).delay(..).multiplier(2).maxDelay(..).jitter(..).predicate(Xxx::isRetryable)).recoverer(recoverer)`, com valores em `shf.<modulo>.retry.*`;
   - `isRetryable` percorre as causas: `AmqpRejectAndDontRequeueException` → não; `TransientDataAccessException`, `RecoverableDataAccessException`, `DataAccessResourceFailureException`, `CannotCreateTransactionException` → sim; qualquer outra (bug, dado inválido) → não.
2. **`XxxFailureRecoverer implements MessageRecoverer`** (`@Component`): loga só `messageId`, `type` e a **classe** da causa, e lança `AmqpRejectAndDontRequeueException`, para o broker mandar a mensagem à DLQ.
3. **`XxxEventListener`** (`@Component`): `@RabbitListener(queues = QUEUE, containerFactory = CONTAINER_FACTORY)` recebendo `Message`. Um método estático `target(Message, JsonMapper)` (testável sem Spring) faz `EventEnvelope.from(message, json)` e rejeita com `AmqpRejectAndDontRequeueException` o que não for esperado (tipo, versão, `workspaceId` nulo). Coloca o `correlationId` no MDC e restaura o valor anterior no `finally`. Chama o caso de uso e mais nada.
4. **Caso de uso consumidor** em `application/usecase` com `public static final String CONSUMER = "<modulo>.<proposito>"` e `@Transactional`:
   ```java
   if (!processedEvents.markProcessed(CONSUMER, eventId)) {
       log.info("... already processed eventId={} workspaceId={}", eventId, workspaceId);
       return false;
   }
   // efeito: atualizar projeção OU store.markDirty(workspaceId, clock.instant(), quiet, maxWait)
   meters.counter("shf.<modulo>.<metrica>").increment();
   ```
   A marca na inbox e o efeito commitam juntos. O ack só sai depois do commit.

JSON usa **Jackson 3** (`tools.jackson.databind.json.JsonMapper`), não `com.fasterxml.jackson`.

## Read models com debounce + job

Quando um evento dispara um recálculo caro (Health, Insights), o consumidor **não calcula**: só marca o Workspace (`<modulo>_workspaces.dirty_since/due_at`, debounce `shf.<modulo>.debounce.quiet/max-wait`). Um `@Scheduled` em `infrastructure/scheduling/XxxJob` pega os vencidos com `FOR UPDATE SKIP LOCKED` (serializa por Workspace e funciona com várias instâncias), recalcula do zero a partir das portas de leitura e publica o próprio evento `<modulo>.<algo>-updated`. Rebuild na inicialização: `infrastructure/startup/XxxRebuildRunner` controlado por `shf.<modulo>.rebuild`. O cálculo é **determinístico e idempotente**: recalcular com os mesmos dados dá o mesmo resultado.

## Observabilidade e privacidade

- Logs com `eventId`, `eventType`, `workspaceId` e contagens, nunca o payload nem a mensagem da exceção.
- Contadores Micrometer `shf.<modulo>.*` (marcados, processados, falhas). A DLQ é monitorada e reprocessada por runbook (`docs/runbooks`).

## Testes

- `XxxEventListenerTest` (unitário): `target` aceita o evento esperado e rejeita outro tipo, outra versão e `workspaceId` nulo; `isRetryable` é verdadeiro só para falhas transitórias. Modelo: `InsightsEventListenerTest`.
- Caso de uso consumidor com fake de `ProcessedEvents`: a segunda entrega do mesmo `eventId` não tem efeito.
- `XxxWorkerIT` ponta a ponta: comando na API → evento → efeito observado com Awaitility; reentrega idempotente; mensagem inválida termina na `.dlq`; logs sem dado financeiro.
- **Atualize `IdentityTables.awaitQuiescence`** com o novo `consumer`/`event_type` (e a tabela `*_workspaces`, se houver), senão as ITs limpam tabelas com trabalho assíncrono em andamento e ficam instáveis.
- Debounce e intervalos curtos para teste em `src/test/resources/application-test.yml`.

## Checklist

- [ ] `IntegrationEvent` gravado na outbox, na mesma transação, só quando mudou; payload só com ids
- [ ] Fila durável + DLQ + binding por nome de evento; container factory com `defaultRequeueRejected=false`
- [ ] Retry stateless só para falhas transitórias; recoverer manda para a DLQ
- [ ] Consumidor idempotente via `ProcessedEvents` na mesma `@Transactional` do efeito
- [ ] `awaitQuiescence` atualizado; listener test + worker IT; ADR registrada
