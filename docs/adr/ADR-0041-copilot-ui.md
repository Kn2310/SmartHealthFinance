# ADR-0041 — UI do Copilot (telas, estados, pontos de entrada)

## Status

Accepted

## Context

A Parte D da Sessão 8.2 entregou a API de conversa do Copilot (ADR-0040). A Parte E entrega as telas `D-Copilot`,
`D-CopilotChat`, `D-CopilotStates`, `M-Copilot` e `M-CopilotChat` (design/specs/03-screens.md, 05-states.md,
06-responsive.md) e troca os placeholders "Perguntar ao Copilot · Em breve" registrados nos ADR-0024, ADR-0029 e
ADR-0037. O layout tem lacunas: rota de uma conversa sem histórico, uso com `[LIMITE DO PLANO]` (planos são do M12),
feedback (aprovado no ADR-0040 §8, ausente do layout) e textos de estados que não existem no design.

Decisões aprovadas pelo usuário em 2026-11-12 (Sessão 8.2, Parte E).

## Decision

### 1. Rotas e navegação

- `/copilot` (`D-Copilot`/`M-Copilot`): pergunta, escopo de dados (`dataScope` do `GET /usage`, "abr a set"), aviso
  "as decisões são suas", sugestões, o que o Copilot faz e não faz; coluna lateral (desktop) com Nova pergunta,
  Histórico e Uso no mês. No mobile, o histórico fica na própria tela, abaixo das sugestões (M-Copilot), e o campo
  fica fixo acima da navegação.
- **Conversa efêmera** (sem `COPILOT_HISTORY`): ao perguntar, `/copilot` passa a mostrar o chat (`D-CopilotChat`) sem
  mudar a URL. Recarregar volta ao início. As até 3 perguntas anteriores vão no corpo (`previousQuestions`).
- **Conversa guardada** (com `COPILOT_HISTORY`): depois da primeira resposta, a URL é trocada (`replace`) por
  `/copilot/{conversationId}`; o histórico abre `/copilot/{id}`. Uma resposta aberta do histórico mostra "Resposta de
  {data}. Os números podem ter mudado desde então."
- **Contexto de entrada na URL** só com tipo e ids (ADR-0040 §3): `/copilot?from=insight&insightId=`,
  `?from=invoice&cardId=&month=`, `?from=installment&cardId=&planId=`, `?from=goal&goalId=`. A tela mostra as
  sugestões do contexto; nada é enviado sem a pessoa confirmar. Pergunta e valores nunca vão para a URL.
- Navegação existente: "Copilot" na sidebar e "IA" na bottom nav (04-navigation-flows, N-Map) passam a abrir a tela.

### 2. Resposta (AI Response)

- Sem avatar, rótulo "✦ Copilot". O texto é renderizado **só a partir dos segmentos tipados** (`TEXT` como texto,
  `VALUE` pelo `ref` em `values`, em negrito, com o rótulo `Estimado`/`Simulado` quando o `kind` pede). Nunca
  `dangerouslySetInnerHTML`, nunca markdown: sem dependência nova.
- Quadro de fatores (`breakdown`) como tabela com total quando vier; "Como cheguei a isso" recolhível com a
  explicação; avisos (`notices`); Confiança (alta/média/baixa, sem a razão); Dados usados em badges; ações como links
  para as telas existentes (rota montada pela UI a partir de `kind` e `params`); Copiar (texto plano da resposta).
- ✦ só onde o design indica conteúdo gerado (rótulo do Copilot, sugestões, campo de pergunta).
- Região `aria-live="polite"` para a resposta e o estado de carregamento; foco vai para a resposta quando ela chega.
- Aviso fixo: "O Copilot pode errar. Confira os dados usados em cada resposta."

### 3. Uso e limites (quota provisória até o M12)

- "Uso no mês: {usado} de {limite} perguntas", com os números do `GET /usage`. Sem nome de plano e sem "Conhecer o
  plano" até o M12.
- Estado Limite: "Você usou as perguntas deste mês" (ou "de hoje"). "Novas perguntas liberam em {data}. Insights e
  análises continuam disponíveis." com "Ver insights".

### 4. Feedback

- Dois botões de ícone rotulados ("Resposta útil", "Resposta não útil") na linha de ações, ao lado de Copiar, só em
  respostas `OK` e modo seguro. "Não útil" abre a lista dos 4 motivos (opcional). Depois: "Obrigado pelo retorno".

### 5. Textos fixos

- Catálogo aprovado (sugestões iniciais e por ponto de entrada, continuação por intenção, avisos e estados) fica em
  `features/copilot/messages.ts`; as respostas vêm do backend. Estados: sem consentimento (link para
  `/profile/consents`), sem dados, carregando (mensagem extra após 10 s), limite, muitas perguntas (com os segundos do
  `Retry-After`), modo seguro, indisponível e erro (texto do layout, com Tentar novamente e a tela do contexto ou
  Insights), já respondida, histórico desligado (link para Consentimentos).

### 6. Pontos de entrada

| Onde | Antes | Agora |
|---|---|---|
| Home, Ações rápidas | "Em breve" (ADR-0037) | `/copilot` |
| `D-InsightDetail` | `ASK_COPILOT` com `available=false` (ADR-0021/0024) | `/copilot?from=insight&insightId=` |
| `D-Invoice` | "Em breve" (ADR-0029) | `/copilot?from=invoice&cardId=&month=` |
| `D-Installment` | "Em breve" (ADR-0029) | `/copilot?from=installment&cardId=&planId=` |

- **Adendo (2026-11-12, aprovado pelo usuário):** na fatura, a entrada fica no cabeçalho de **toda** fatura, além do
  bloco "✦ Explicação" (que só existe com o insight D11). Textos aprovados fora do catálogo inicial: conversa não
  encontrada, confirmações de apagar uma ou todas as conversas, erros do histórico e de apagar, o rótulo "Motivo", o
  escopo em anos diferentes ("abr de 2025 a set de 2026") e a continuação das demais intenções; 409 em andamento,
  contexto não encontrado e 400 usam o estado genérico de erro. Rotas reais das ações: `OPEN_HEALTH` → `/home/health`,
  `OPEN_FORECAST` → `/home#events`, `OPEN_INSTALLMENTS` → `/cards/{id}?tab=installments`. "Copiar" monta o texto plano
  dos segmentos (o contrato não expõe `text`).
- `ASK_COPILOT` no backend (Insights): os detectores passam a gerar a ação disponível, e a resposta da API a marca como
  disponível também nos insights já gravados (decisão na leitura, sem rebuild). A versão dos insights não muda.

### 7. BFF

- Rotas `/api/bff/copilot/*` para as 7 rotas do ADR-0040, com `withSession`, `rejectCrossOrigin` nas mutações,
  `noStoreJson` e corpo remontado. A `Idempotency-Key` é gerada no cliente por envio (reutilizada só no retry do mesmo
  envio) e repassada pelo BFF. O BFF repassa `Retry-After`. Só códigos estáveis chegam ao cliente.
- Timeout do BFF acima do orçamento de 30 s do pipeline. CSP inalterada.

## Alternatives

- Flag ignorada só no frontend para `ASK_COPILOT`: o contrato mentiria. Rejeitada.
- Rota `/copilot/nova` para a conversa efêmera: rota que não existe ao recarregar. Rejeitada.
- Mostrar só o usado, sem limite; ou manter "Conhecer o plano" "Em breve": rejeitadas (a primeira esconde um limite
  real; a segunda promete o M12).
- Feedback como pergunta abaixo da resposta: mais ruído visual. Preterido.
- Markdown na resposta: dependência nova e risco de HTML. Rejeitado em favor dos segmentos.

## Consequences

- O Copilot fica utilizável de ponta a ponta com o adapter simulado; a quota provisória aparece com números reais.
- Recarregar uma conversa efêmera perde a conversa (por desenho: nada é guardado sem consentimento).

## Security / Privacy

Nada sensível em URL (só tipo e ids); resposta sem HTML cru; o browser fala só com o BFF; CSP preservada; nenhum
`console.*` com conteúdo.

## Financial Integrity

Nenhum cálculo no frontend: números, quadro e confiança vêm do backend (ADR-0039 §7, ADR-0040 §3).

## Operational Impact

Sem dependência nova. Contrato manual `schema.d.ts`/`contract.ts` atualizado com o ADR-0040. Mudança pequena no
módulo Insights (disponibilidade de `ASK_COPILOT`). E2E com o adapter simulado.

## Related Specs

design/specs 02 (AI Response), 03 (COPILOT), 04, 05 (Estados do Copilot), 06, 07; N-Map; ADR-0007, ADR-0021,
ADR-0024, ADR-0029, ADR-0037, ADR-0038, ADR-0040.

## Date

2026-11-12
