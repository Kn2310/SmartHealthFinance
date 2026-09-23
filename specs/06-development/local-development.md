# Local Development

## Princípio

O ambiente local deve reproduzir os componentes necessários sem depender de serviços pagos.

## Infra local

Docker Compose:
- PostgreSQL
- Redis
- RabbitMQ

Aplicações:
- Spring Boot API
- workers quando necessários
- Next.js

## Dados

Usar seeds de desenvolvimento sintéticos.

Nunca usar dados financeiros reais.

## Flyway

Toda mudança de schema passa por migration versionada.

## Observability

Executar OpenTelemetry localmente quando possível.

## Testes

Testcontainers para integração.
Unit tests sem dependência de infraestrutura externa.

## AI

Desenvolvimento deve permitir fake/mock provider para não depender de custo de LLM.

## Configuração

Secrets locais via environment/secret files ignorados pelo Git.

## Objetivo

Qualquer desenvolvedor deve conseguir clonar o repositório e iniciar o sistema localmente com instruções documentadas.
