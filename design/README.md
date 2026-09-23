# Smart Health Finance — MVP Visual (Design & UX)

Pacote de especificações e layouts do MVP 1.0, exportado do canvas de Design.
Escopo: exclusivamente Product Design / UX / UI (sem backend, APIs ou implementação).

> **Dados de exemplo.** Todas as telas usam dados fictícios (Marina Souza, Banco Aurora, Banco Norte, Conta Horizonte, Loja Tech) apenas para composição visual. Os números são coerentes entre si — veja `specs/09-dados-de-exemplo.md`.

## Como usar

1. Abra `index.html` no navegador: galeria de todas as telas, com miniatura, HTML e PNG.
2. Para navegar o protótipo offline, abra `layouts/html/Main.html` (ou `D-Welcome.html`, `D-Home.html`, `M-Home.html`) e clique nos links — as telas se conectam como no canvas.
3. Leia as specs em `specs/` na ordem numérica.

## Estrutura

```text
smart-health-finance-mvp-design/
├── index.html                 Galeria navegável de todas as telas
├── README.md
├── screens.json               Manifesto: id, título, tamanho, página, links de saída
├── specs/
│   ├── 01-foundations.md      Cores, tipografia, espaço, raio, grid, elevação, movimento, ícones
│   ├── 02-components.md       Foundation (30) + Financial (15) + Intelligence (9)
│   ├── 03-screens.md          Cada tela: objetivo, hierarquia, ações, estados, rota
│   ├── 04-navigation-flows.md Arquitetura de informação e jornadas do protótipo
│   ├── 05-states.md           Loading, Empty, Error, Success, Locked, Unavailable, Partial, Stale…
│   ├── 06-responsive.md       Regras por breakpoint
│   ├── 07-design-decisions.md
│   ├── 08-mvp-coverage.md     Checklist do brief → artboards
│   └── 09-dados-de-exemplo.md Dataset fictício e a matemática que o mantém consistente
├── tokens/
│   ├── tokens.json            Tokens de design (cores, tipo, espaço, raio, sombra, motion…)
│   └── tokens.css             Os mesmos tokens como CSS custom properties
├── layouts/
│   ├── png/                   79 imagens (desktop 1x; mobile e tablet 2x)
│   └── html/                  79 telas HTML estáticas, interligadas, funcionam offline
├── fonts/                     Inter e Manrope (woff2, licença OFL incluída)
└── source/project/            Fonte original do canvas (.dc.html + canvas.json)
```

## Convenções de nomes

| Prefixo | Conteúdo | Largura |
|---|---|---|
| `Main`, `O-` | Capa, inventário, decisões, cobertura | 1440 |
| `F-` | Foundations | 1440 |
| `C-` | Component library | 1440 |
| `D-` | Telas desktop | 1440 |
| `M-` | Telas mobile | 390 |
| `T-` | Tablet | 834 |
| `XL-` | Large desktop | 1680 |
| `S-`, `R-`, `N-` | Estados, regras responsivas, navegação | 1440 |

## Placeholders (a definir pelo produto)

Itens que a especificação não define aparecem entre colchetes nas telas e **não** foram inventados:

- `[NOME DO PLANO]`, `[LIMITE DO PLANO]` e quais recursos são premium
- Metodologia de ponderação do score (pesos das 6 dimensões)
- Faixas de interpretação do score (ex.: "bom", "atenção") — não criadas
- `[Regras de senha a definir]`, `[CANAL DE SUPORTE]`
- `[Informado no arquivo]` para pagamento mínimo e juros de parcelamento

## Observações

- Os HTMLs são referências visuais, não código de produção.
- Na tela `D-GoalDetail` a simulação é interativa no canvas original (R$ 700 / 780 / 900). No HTML estático, ela aparece no estado padrão (R$ 780).
- O símbolo ✦ usa a fonte do sistema (não está no subconjunto latino da Inter).
