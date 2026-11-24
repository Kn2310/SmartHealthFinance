# Seed fictício do staging

Script Node 22 sem dependências (`seed.mjs`) que carrega os dados de `design/specs/09-dados-de-exemplo.md` **pela API
pública** (ADR-0044 §8, item 35): token por password grant, `POST /users/me`, contas, importação de CSV, cartões e
objetivos. Nenhum SQL; Analytics, Health, Forecast e Insights se formam pelos eventos, como em produção.

O wrapper do staging cria os usuários e liga o client `shf-staging-seed` no Keycloak, roda este script num container
`node:22-alpine` na rede do compose e desliga o client em seguida (inclusive em caso de falha).

## Entrada (variáveis de ambiente, todas obrigatórias)

| Variável | Exemplo |
|---|---|
| `SEED_API_BASE_URL` | `http://api:8080` |
| `SEED_TOKEN_URL` | `https://auth.staging.<domínio>/realms/smart-health-finance/protocol/openid-connect/token` |
| `SEED_CLIENT_ID` / `SEED_CLIENT_SECRET` | `shf-staging-seed` / segredo do env file |
| `SEED_MARINA_USERNAME` / `SEED_MARINA_PASSWORD` | usuária com dados completos |
| `SEED_BRUNO_USERNAME` / `SEED_BRUNO_PASSWORD` | usuário só provisionado, sem dados |

Senhas e segredos só por variável de ambiente, nunca por argumento. Sai com código ≠ 0 em qualquer erro. Os logs têm só
nome da etapa, status HTTP e contagens.

```sh
docker run --rm --network <rede-do-compose> -v "$PWD/infrastructure/staging/seed:/seed:ro" \
  --env-file <arquivo-de-env-do-seed> node:22-alpine node /seed/seed.mjs
```

## O que cria para a Marina

Datas relativas a hoje (fuso `America/Sao_Paulo`), histórico de 6 meses (5 anteriores mais o corrente), nada no futuro.

- **Contas:** Banco Aurora (corrente, R$ 6.240,35), Banco Norte · Reserva (poupança, R$ 10.000,00), Conta Horizonte
  (pagamento, R$ 2.180,00) e Cofre Viagem Lisboa (R$ 4.300,00, fora do total). Os saldos iniciais entram como
  `ADJUSTMENT` datado no início do histórico; o do Aurora é calculado para que o saldo final seja o do exemplo.
- **Transações:** importação CSV (modelo canônico, ADR-0009 §6) no Aurora, com a idempotência do upload: salário,
  freelance (2 meses), aluguel, energia, mercado, transporte, streaming e alimentação fora com Entrega Já Delivery, Bistrô
  Lume, Pizzaria Forno e Café Grão (25 compras/mês, R$ 1.284,00 no mês corrente cheio). Dinheiro em centavos `BigInt`.
- **Cartões:** Aurora Platinum •••• 4821 (R$ 12.000, fecha 28, vence 5) e Horizonte Gold •••• 0937 (R$ 6.000, fecha 5,
  vence 12), e uma **fatura do Aurora** importada (CSV de fatura, ADR-0009 §22) com o notebook 3/10 de R$ 480,00.
- **Objetivos:** Reserva de emergência (alvo R$ 25.152,00, aporte R$ 800/mês, sem prazo) e Viagem para Lisboa (R$ 12.000,
  R$ 700/mês, prazo daqui a 10 meses).

Para o Bruno, só provisiona o usuário e confere que o Workspace da Marina responde 404 ao token dele.

**Consentimento do Copilot: não é criado.** Quem decide é o usuário, no teste da Parte E.

## Idempotência

Por usuário: se a Marina já tem alguma conta (inclusive arquivada), o script pula o seed e registra isso no log; rodar
duas vezes dá o mesmo estado. Se uma execução falhar **no meio**, a Marina fica com dados parciais e a repetição
também pula. Nesse caso, use o reset do staging (ADR-0044 §8, item 38).

## Diferenças em relação ao exemplo

- Datas relativas, não "22 de setembro de 2026"; o mês corrente é parcial até hoje.
- Receitas e despesas históricas seguem o espírito do exemplo (alimentação fora varia como abr–ago; o resto repete o
  mês de setembro, sem condomínio); scores de Health e valores de fatura de meses anteriores **não** são reproduzidos.
- O Viagem para Lisboa precisa de um destino dedicado que não seja poupança (ADR-0034 §2.1), por isso o Cofre Viagem
  Lisboa. Os R$ 4.300 entram como saldo inicial, não como 6 aportes mensais.
- A fatura do Horizonte Gold não é importada e as parcelas 1 e 2 do notebook ficam fora (início do acompanhamento do
  cartão, ADR-0025 §15). O pagamento da fatura pela conta não é conciliado.
- O objetivo "Guardar a diferença do delivery" não é criado (nasce de um fluxo da UI).
- Sem CPF, número de conta ou agência; nomes de instituição e comerciantes são fictícios.

## Testes

Sem rede externa (funções puras e o fluxo completo contra uma API simulada em `127.0.0.1`):

```sh
node --test infrastructure/staging/seed/seed.test.mjs
```
