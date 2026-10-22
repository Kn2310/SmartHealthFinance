# Runbook — Cartões: job `invoice.closed` e datas de fatura

Referência: ADR-0025 (modelo de Cartões), ADR-0026 §5 e §8 (datas congeladas e job), spec 05.7 (outbox,
at-least-once), 05.9 (métricas).

Valor, estado e utilização das faturas **nunca são gravados**: são recalculados a cada leitura a partir das parcelas,
créditos e pagamentos (`invoice-v1`). As tabelas `card_invoices` e `card_installments` guardam só datas e parcelas.
Nada aqui recalcula ou corrige valores.

## Como o `invoice.closed` sai

1. Às 00:10 do fuso de negócio (`shf.cards.invoice-closed.cron`, fuso `shf.overview.zone`), o `InvoiceClosedJob`
   procura as faturas com lançamentos cujo fechamento já passou (`closing_date ≤ hoje`) e ainda não notificadas
   (`closed_notified_at` nulo), em lotes de 200, com `FOR UPDATE SKIP LOCKED`.
2. Para cada uma, na mesma transação, grava `invoice.closed` v1 (`cardId`, `invoiceId`, `dueMonth`) na outbox e marca
   `closed_notified_at`. O relay publica no exchange `shf.events` como qualquer evento.
3. Fatura sem lançamentos não tem linha, então não gera evento.

## Sinais

- Log `Invoice closed job failed error=...` (só a classe da exceção).
- Métrica `shf.cards.invoice.closed` (contador de eventos gravados) parada por mais de um dia com cartões ativos.
- Consulta de diagnóstico, que só devolve ids e datas:

  ```sql
  select id, workspace_id, card_id, closing_date
  from card_invoices
  where closed_notified_at is null and closing_date < current_date
  order by closing_date
  limit 50;
  ```

## Reprocessar

O job é idempotente: rodar de novo só pega as linhas ainda não marcadas. Para antecipar a próxima execução, reinicie
uma instância com `SHF_CARDS_INVOICE_CLOSED_CRON` apontando para alguns minutos à frente (formato cron do Spring, seis
campos) e volte ao padrão depois.

Nunca apague `closed_notified_at` à mão para "reenviar": os consumidores (Sessão 6.2) são idempotentes pela inbox,
mas um reenvio sem motivo registrado é replay não auditado (05.7). Se for preciso, registre quem, quando, quantas
faturas e o motivo no canal de incidentes antes.

## Datas de fatura

- **Fechada = congelada.** Uma linha com `closing_date ≤ hoje` nunca muda, nem quando o ciclo do cartão muda.
- **Mudança de ciclo:** o `PUT` do cartão recalcula as linhas ainda não fechadas na mesma transação. Se duas faturas
  passariam a vencer no mesmo mês, o `PUT` é recusado (`dueDay DUE_MONTH_CONFLICT`) e nada muda. Orientação ao
  usuário: ajustar o dia depois do vencimento da fatura em conflito.
- **Não edite datas à mão.** Datas reais do banco chegam pela importação (Sessão 6.2), com `dates_source = IMPORTED`.

## O que nunca aparece em log

Apelido, `last4`, limite, valores de fatura ou descrições de compra. Os logs de Cartões têm só `cardId`, `invoiceId`
e `workspaceId`. Número completo, CVV e validade não existem no sistema.
