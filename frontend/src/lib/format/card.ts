/**
 * Rótulo de um cartão para listas e detalhes: apelido + 4 últimos dígitos ("Aurora Platinum •••• 4821"). O sistema
 * nunca guarda nem exibe o número completo (ADR-0025 §1).
 */
export function cardLabel(card: { nickname: string; last4: string }): string {
  return `${card.nickname} •••• ${card.last4}`
}
