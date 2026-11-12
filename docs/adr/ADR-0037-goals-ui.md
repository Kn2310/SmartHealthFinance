# ADR-0037 — UI de Objetivos (M10, Sessão 7.2, parte D)

## Status

Accepted

> Decisões aprovadas em 2026-11-06:
> - **Simulation Card:** chips do **plano atual** e do **mínimo para cumprir o prazo** (calculado pelo backend), mais um
>   campo **"Outro valor"**; a faixa de sugestão do D-Goals usa o mesmo mínimo, só com objetivo atrasado e prazo, sem ✦;
> - **D-CreateGoal:** campo **Tipo** (Reserva/Compra/Outro; Reserva indisponível se já existe) e **"Conta do objetivo"**
>   (fora da reserva), "Conta de origem dos aportes" opcional e "Já guardado" só leitura, vindo da prévia;
> - **Home:** bloco **"Ações rápidas" completo** (Importar extrato, Adicionar conta, Perguntar ao Copilot "Em breve",
>   Criar objetivo) em todas as larguras;
>   - **Adendo (2026-11-12, ADR-0041 §6):** "Perguntar ao Copilot" deixa de ser "Em breve" e leva a `/copilot`.
> - **"Registrar aporte"** num painel (drawer; bottom sheet no mobile) e **edição** em `/goals/:id/edit`, com
>   Pausar/Retomar, Concluir e Arquivar, cada um com confirmação.

## Context

O backend dos Objetivos está pronto (ADR-0034 modelo, ADR-0035 API, ADR-0036 previsão e simulação). Faltam as telas
`D-Goals`/`M-Goals`, `D-GoalDetail`, `D-CreateGoal` e `D-GoalCreated`, e os placeholders "Em breve" ligados a
objetivos: "Criar objetivo de economia" nos Insights (adendos aos ADR-0021 e ADR-0024), o passo da reserva no Health
(adendo ao ADR-0020), "Objetivos" no M-More e a ação rápida da Home (03-screens). O layout não mostra o formulário de
aporte, a edição nem as ações de status, e o D-CreateGoal não tem tipo nem conta do objetivo.

## Decision

### 1. BFF

`/api/bff/goals` (lista com `?status=`, criação), `/recent-contributions`, `/preview`, `/{id}` (detalhe, edição),
`/{id}/plan`, `/{id}/{pause|resume|complete|archive}`, `/{id}/contributions` (histórico; aporte com
`Idempotency-Key` obrigatória, repassada como veio), `/{id}/simulations` e `/{id}/projection`. O Workspace vem sempre da
sessão; o BFF confere só o formato (texto, dinheiro em string com até 2 casas, mês `AAAA-MM`, UUIDs) e remonta o corpo
com os campos do contrato (moeda sempre BRL); mutações e os POSTs de simulação/prévia exigem a mesma origem; id que não
é UUID recebe o mesmo `404 GOAL_NOT_FOUND`; falhas viram códigos estáveis (`GOAL_*`, `ACCOUNT_*`, `CONFLICT`,
`IDEMPOTENCY_KEY_REUSED`, `VALIDATION_FAILED` só com campos conhecidos) e o resto `502 UNAVAILABLE`. Logs só com rótulo e
status.

### 2. `/goals` (D-Goals, M-Goals, D-GoalCreated)

- Métricas: guardado (soma do backend), aportes planejados e "≈ N% da sua renda média", "N de M" no ritmo; sem
  previsão, "—" e o motivo.
- Faixa "Simulação" (sem ✦): o primeiro objetivo ativo atrasado com prazo, com o cenário só de prazo
  (`requiredMonthlyForDeadline`, ex.: R$ 770,00 no 09-dados) e o link "Simular" para `/goals/{id}#simular`.
- Goal Cards (02-components): ícone por tipo (reserva guarda-chuva, compra sacola, outro alvo), nome (link), prazo,
  badge com texto (prazo, "N meses após o prazo" — "+N meses" no mobile —, "Sem prazo", "No prazo", "Pausado"…),
  guardado/alvo, barra (SVG, sem estilo inline), % e "Previsão: mês/ano ≈ Estimado" ou o motivo de não haver previsão.
- Filtro Em andamento / Concluídos / Arquivados na URL (`?status=`); aportes recentes com data, conta de origem e
  "Confirmado".
- D-GoalCreated: a criação navega para `/goals?created={id}&first={mês}` (só id e mês, sem valores): o novo vem
  primeiro com "Novo" e o toast "Objetivo criado. O primeiro aporte está previsto para {mês}." com "Ver objetivo".

### 3. `/goals/:id` (D-GoalDetail)

- Progresso (fato) com Concluído, Faltam, Prazo, Aporte planejado, Previsão (Estimado, faixa e confiança) e o ritmo
  real; avisos de recálculo, pausa, conclusão, conta arquivada e reserva sem poupança.
- Gráfico `GoalProjection` do DS: guardado sólido (fato), projeção tracejada (Estimado), linha de prazo, tabela
  "Ver dados" com os pontos do `GET /projection`. A legenda da projeção é "Projeção pelo aporte planejado" (o layout
  diz "no ritmo atual", mas a central do ADR-0034 §4 é o plano).
- Aportes: saldo inicial do destino e os movimentos (Aporte, Resgate, Movimento) com sinal e "Confirmado".
- Simulation Card (§ decisões): Simulado (Teal, borda pontilhada), resultado com mês, prazo, aportes, primeiro aporte e
  peso na renda; "Aplicar R$ X/mês ao objetivo" só quando difere do plano, com confirmação inline que diz o que muda e
  que nenhum saldo ou transação muda; aplica pelo `PUT /plan` mantendo o prazo.
- "Registrar aporte": drawer modal nativo (foco preso, Esc/fundo fecham, foco volta), origem padrão do objetivo, conta
  de reserva só na reserva (sem poupança como origem), valor e data; uma `Idempotency-Key` por intenção: reenviar o
  mesmo corpo depois de uma falha de rede reusa a chave; um corpo diferente ou um pedido recusado ganha chave nova; o
  drawer não fecha enquanto envia (fechar perderia a chave).
- O Simulation Card remonta quando o plano, o guardado ou o prazo mudam (depois de um aporte ou de "Aplicar"), para
  que o chip do plano e o do mínimo nunca mostrem a simulação anterior.

### 4. `/goals/new` e `/goals/:id/edit`

- Origem pela URL só como tipo e id: `?origin=INSIGHT&insightId=` (a sugestão é relida do detalhe do insight:
  nome "Guardar a diferença em {assunto}", alvo, aporte e prazo do `CREATE_SAVINGS_GOAL`) ou
  `?origin=HEALTH&type=EMERGENCY_RESERVE`. Sem a ação disponível, a criação é manual.
- Prévia Simulada ao vivo (`POST /preview`, 400 ms depois da última tecla, a anterior fica visível): mês, prazo, aportes,
  primeiro aporte, peso na renda e o mínimo para o prazo quando não cabe. Sem aporte digitado, a prévia vai com zero
  para trazer o "Já guardado" e o alvo sugerido da reserva, que preenche o alvo enquanto ele está vazio.
- Edição com o mesmo formulário (tipo só leitura) e a seção "Status do objetivo" com as ações permitidas no status.

### 5. Integrações

- Insights: `CREATE_SAVINGS_GOAL` vira link para `/goals/new?origin=INSIGHT&insightId=`; "Perguntar ao Copilot" segue
  "Em breve" (M11).
- Health: `BUILD_RESERVE` leva a "Ver objetivo de reserva" ou "Criar objetivo de reserva" (sem a lista, a
  "Ver objetivos", sem arriscar uma segunda reserva) e, com a previsão do objetivo, mostra "Com aportes de R$ X/mês,
  sua reserva chega ao alvo de R$ Y em {mês} ≈ Estimado".
- M-More: "Objetivos" sem "Em breve". Home: "Ações rápidas" ao lado de "Próximos eventos" (abaixo no mobile/tablet);
  sem contas (`NO_ACCOUNTS`) o bloco não aparece, porque a Home vazia mostra só o próximo passo, "Adicionar conta"
  (`home-empty.spec.ts`).

### 6. Estilo e contrato

Só tokens do DS, sem estilo inline (CSP), breakpoints 640/1024/1440, sem rolagem horizontal nas 5 larguras. `contract.ts`
ganha `GOAL_TYPES`, `GOAL_STATUSES`, `GOAL_ORIGIN_KINDS`, `GOAL_NAME_MAX_LENGTH` e `GOAL_MAX_SCENARIOS`, com teste de
drift contra o Java. Sem mudança de API nem de backend.

## Alternatives

- Chips fixos (+R$ 100/+R$ 200) ou o mínimo arredondado do layout (R$ 780/900): soma ou regra de arredondamento no
  frontend, e o chip pode não cumprir o prazo.
- Tipo inferido na criação: "Compra" nunca seria escolhido.
- Ações rápidas só a partir de 1024 px: fiel ao M-Home, mas sem atalho para criar objetivo pela Home no celular.
- Aporte pela tela de nova transação: perde a regra da conta de reserva e o destino fixo do objetivo.

## Consequences

- A lista faz até três chamadas (objetivos, aportes recentes, simulação da faixa) e o detalhe cinco (objetivo,
  projeção, histórico e duas simulações), todas independentes; uma falha secundária não derruba a tela.
- A frase da reserva no Health diz "chega ao alvo de R$ Y" em vez de "chega a 6 meses de despesas": o alvo é editável e
  pode não ser 6 meses. Aprovado em 2026-11-06 e registrado como adendo ao ADR-0020.
- Dívidas: "Ver todos" dos aportes recentes (sem tela própria; o layout tem o link), redução de categoria na UI de
  simulação (o motor aceita), ícone de viagem do layout (os tipos são Reserva/Compra/Outro).

## Security / Privacy

- O browser só fala com o BFF; o Workspace vem da sessão; a URL nunca leva valores (só ids, tipo, origem e mês).
- Nada de nomes, valores ou corpo em logs do BFF ou do browser.

## Financial Integrity

- O frontend só exibe: progresso, previsão, simulação, projeção, mínimo para o prazo e peso na renda chegam prontos.
  A UI só formata, converte o texto digitado em string decimal e compara strings decimais para saber se o valor
  simulado é o plano atual.
- Fato (Confirmado), Estimado e Simulado têm selos próprios e nunca se somam; "Aplicar" só muda o plano, depois de
  confirmar.

## Operational Impact

- E2E `frontend/e2e/goals.spec.ts`: reserva a partir do Health, aporte, progresso exato, previsão Estimada, simulação,
  "Aplicar" e a conferência de que saldo, fluxo do mês e Health não mudam ao simular e aplicar; as 5 larguras. Rotas
  novas no aquecimento do `globalSetup`; `health.spec.ts` passa a esperar "Criar objetivo de reserva".

## Related Specs

- `design/specs/02-components.md`, `03-screens.md`, `04-navigation-flows.md`, `05-states.md`, `06-responsive.md`,
  `07-design-decisions.md`; layouts `D-Goals`, `M-Goals`, `D-GoalDetail`, `D-CreateGoal`, `D-GoalCreated`, `D-Home`,
  `M-More`, `N-Map`
- ADR-0016, 0020, 0021, 0024, 0033, 0034, 0035, 0036

## Date

2026-11-06
