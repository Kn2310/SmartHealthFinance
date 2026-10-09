# Fixtures de E2E

Arquivos usados pelos testes Playwright (`frontend/e2e/`). **Todos os dados são fictícios** (specs
06-development/local-development.md: "Nunca usar dados financeiros reais"): nomes de empresas e pessoas
inventados ("Fictícia", "Exemplo", "Teste"), identificadores no formato `FIC-AAAA-NNNN`, nenhum número de conta,
agência, CPF ou documento.

| Arquivo | Conteúdo | Uso |
|---|---|---|
| `extrato-ficticio-2026-jul-set.csv` | 51 lançamentos de 01/07/2026 a 28/09/2026 no modelo CSV canônico (ADR-0009 §6): `data;descricao;valor;id`, separador `;`, vírgula decimal, milhar com ponto, UTF-8 com BOM e CRLF | cenário de importação (preview → confirmação → resultado → selo "Importado") |

Regras:

- Os arquivos são mantidos byte a byte (`.gitattributes`: `-text`), porque BOM, CRLF e separadores fazem parte do
  que o parser precisa aceitar.
- Fixture nova: só dado inventado e óbvio como tal. Nunca derive de um extrato real, nem "anonimizado".
