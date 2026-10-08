/**
 * Textos da importação a partir dos códigos estáveis do backend (ADR-0009 §7). Código desconhecido cai numa
 * mensagem neutra: a UI nunca mostra o código cru nem texto vindo do servidor.
 */

function lookup(table: Partial<Record<string, string>>, key: string | null | undefined, fallback: string): string {
  return (key != null ? table[key] : undefined) ?? fallback
}

/** Arquivo inteiro recusado (`FILE_REJECTED.reason`) ou recusado já no navegador. */
const FILE_REJECTION: Partial<Record<string, string>> = {
  EMPTY_FILE: 'O arquivo está vazio.',
  UNREADABLE_FILE: 'Não conseguimos ler este arquivo. Exporte o extrato de novo em .ofx ou .csv.',
  UNSUPPORTED_FILE_TYPE: 'Use um arquivo .ofx ou .csv exportado do seu banco.',
  FORMAT_MISMATCH: 'O conteúdo não é de um CSV. Se o arquivo é um OFX, renomeie-o com a extensão .ofx.',
  NO_TRANSACTIONS: 'O arquivo não tem nenhuma movimentação.',
  TOO_MANY_LINES: 'O arquivo tem mais de 5.000 movimentações. Divida o período em arquivos menores.',
  MISSING_COLUMN: 'Faltam colunas obrigatórias. O CSV precisa ter data, descrição e valor — use o modelo.',
  DUPLICATE_COLUMN: 'Há colunas repetidas no cabeçalho do CSV.',
  MALFORMED_CSV: 'O CSV está malformado (há aspas sem fechamento).',
  INCONSISTENT_DECIMAL_SEPARATOR: 'Os valores misturam vírgula e ponto como separador decimal.',
  MALFORMED_OFX: 'O OFX está incompleto ou malformado.',
  MULTIPLE_STATEMENTS: 'O OFX traz mais de uma conta. Exporte um arquivo por conta.',
  UNSUPPORTED_STATEMENT_TYPE: 'Faturas de cartão ainda não podem ser importadas. Use o extrato da conta.',
  UNSUPPORTED_CURRENCY: 'Só extratos em reais (BRL) podem ser importados.',
  FILE_TOO_LARGE: 'O arquivo passa de 10 MB.',
}

export const fileRejectionMessage = (reason: string | undefined) =>
  lookup(FILE_REJECTION, reason, 'Não conseguimos importar este arquivo. Confira o formato e tente de novo.')

/** Falhas de chamada ao BFF (fora a recusa do arquivo). */
const REQUEST_FAILURE: Partial<Record<string, string>> = {
  ACCOUNT_ARCHIVED: 'Esta conta está arquivada e não recebe movimentações.',
  ACCOUNT_NOT_FOUND: 'Esta conta não está mais disponível.',
  IMPORT_NOT_FOUND: 'Não encontramos esta importação.',
  IMPORT_STATUS_CONFLICT: 'Esta importação não pode mais ser alterada. Atualize a página para ver o status.',
  IDEMPOTENCY_KEY_REUSED: 'Este envio já foi usado com outro arquivo. Selecione o arquivo de novo.',
  FILE_TOO_LARGE: FILE_REJECTION.FILE_TOO_LARGE,
  FORBIDDEN: 'Não foi possível concluir a ação. Atualize a página e tente de novo.',
}

export const requestFailureMessage = (code: string) =>
  lookup(REQUEST_FAILURE, code, 'Não foi possível falar com o servidor. Seus dados continuam seguros; tente de novo.')

/** Motivo de uma linha recusada. */
const LINE_ISSUE: Partial<Record<string, string>> = {
  INVALID_DATE: 'Data inválida',
  INVALID_AMOUNT: 'Valor inválido',
  AMBIGUOUS_AMOUNT: 'Valor ambíguo (milhar ou decimal?)',
  ZERO_AMOUNT: 'Valor zerado',
  TOO_MANY_DECIMALS: 'Mais de 2 casas decimais',
  TOO_LARGE: 'Valor alto demais',
  DESCRIPTION_REQUIRED: 'Sem descrição',
  INVALID_EXTERNAL_ID: 'Identificador inválido',
  COLUMN_COUNT_MISMATCH: 'Número de colunas diferente do cabeçalho',
}

export const lineIssueMessage = (code: string | undefined) => lookup(LINE_ISSUE, code, 'Linha não reconhecida')

/** Motivo de uma importação que falhou no processamento. */
const FAILURE_REASON: Partial<Record<string, string>> = {
  ACCOUNT_ARCHIVED: 'A conta foi arquivada antes do fim da importação. Reative a conta e envie o arquivo de novo.',
  ACCOUNT_NOT_FOUND: 'A conta não está mais disponível.',
  PROCESSING_ERROR: 'Tivemos um problema ao gravar as movimentações. Nada foi importado; nossa equipe foi avisada.',
}

export const failureReasonMessage = (reason: string | null) =>
  lookup(FAILURE_REASON, reason, 'A importação não pôde ser concluída. Nada foi importado.')

const RECORD_STATUS_LABEL: Partial<Record<string, string>> = {
  VALID: 'Nova',
  IMPORTED: 'Importada',
  DUPLICATE: 'Já importada',
  INVALID: 'Com problema',
}

export const recordStatusLabel = (status: string) => lookup(RECORD_STATUS_LABEL, status, 'Linha')

export const plural = (n: number, one: string, many: string) =>
  `${n.toLocaleString('pt-BR')} ${n === 1 ? one : many}`
