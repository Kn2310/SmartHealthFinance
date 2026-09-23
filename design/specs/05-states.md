# 05 · Estados

Referência visual: `S-Catalog`, `D-HomeLoading`, `D-TxnEmpty`, `D-TxnError`, `D-AccountsEmpty`, `D-CopilotStates`, `M-HomeLoading`.

| Estado | Regra | Exemplo no MVP |
|---|---|---|
| **Loading** | Skeleton com a forma do layout final. Card Navy mantém o fundo escuro com skeletons translúcidos. Acima de 10 s, mensagem de progresso | Home “Atualizando seus dados…”; Copilot “Analisando 3 faturas e 142 transações…” |
| **Empty** | Explica o que está vazio, por quê e o que fazer. Diferenciar “filtro sem resultado” de “primeira vez” | “Nenhuma transação encontrada” (limpar filtros / ver todo o mês); “Nenhuma conta adicionada ainda” (importar / criar manual) |
| **Error** | Sem jargão ou códigos. Dizer o que foi preservado e como resolver | “Não conseguimos ler o arquivo… Nenhum dado foi alterado.” · “Seus dados estão seguros.” |
| **Success** | Toast não bloqueante, com desfazer quando reversível | “Objetivo criado. O primeiro aporte está previsto para outubro.” |
| **Locked / Premium** | Recurso · benefício · plano necessário · ação de upgrade + “Agora não” | Copilot com limite mensal atingido; placeholders `[NOME DO PLANO]`, `[LIMITE DO PLANO]` |
| **Unavailable** | Não é erro: o recurso não existe nesta versão ou não se aplica. Visual neutro, badge “Em breve” | Open Finance em Conexões e em Adicionar conta |
| **Partial** | Mostrar o que existe e o que falta; confiança reduzida | “Faltam as faturas de julho… a média usa só 2 meses” |
| **Stale** | Data do último dado + como atualizar | “Banco Norte sem atualização há 18 dias” |
| **Permission denied** | Consentimento revogado: explicar e oferecer rever | “Insights pausados — você retirou a permissão” |
| **Offline / degraded** | Faixa Navy com o horário dos dados exibidos; ações de rede desabilitadas | “Sem conexão · mostrando dados de hoje, 09:14” |

## Estados do Copilot

| Estado | Comportamento |
|---|---|
| Empty (primeiro uso) | Explica o escopo e oferece sugestões |
| Loading | Pergunta visível + skeleton + o que está sendo analisado |
| Erro | Alerta com Tentar novamente e caminho alternativo (Ver fatura) |
| Limite | Variante de Locked; o restante do produto continua funcionando |
| Dados insuficientes | Diz o limite dos dados e oferece a alternativa possível |
| Fora do escopo | Não faz transações ou pagamentos; oferece a informação relacionada |

## Estados de dado (Trust UX)

Confirmado · Importado · Manual · Estimado (≈, tracejado) · Simulado (pontilhado Teal). Detalhes em `01-foundations.md`.
