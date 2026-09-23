# Design System

## 04.1 Tokens

Spacing:
4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48 / 64 / 80

Breakpoints:
- Mobile <640
- Tablet 640–1023
- Desktop 1024–1439
- Large ≥1440

Grid:
- Desktop: 12 columns, max 1440
- Tablet: 8 columns
- Mobile: 4 columns

Icons:
16 / 20 / 24 / 32

## 04.2 Foundation Components

### Actions
- Button
- Icon Button

### Forms
- Input
- Financial Input
- Select
- Combobox
- Checkbox
- Radio
- Switch
- Date Range
- Filter
- Search

### Navigation
- Tabs
- Breadcrumb

### Data Display
- Card
- Metric Card
- Badge
- Tag
- Avatar
- Progress
- Divider

### Feedback
- Toast
- Alert
- Tooltip
- Popover
- Skeleton
- Empty
- Error

### Overlays
- Modal
- Bottom Sheet

Todos devem possuir estados consistentes e acessíveis.

## 04.3 Navigation

Desktop:
Sidebar persistente:
- Início
- Cartões
- Análise
- Objetivos
- Insights
- Copilot
- Perfil

Header com contexto e ações.

Rotas:
- `/home`
- `/cards`
- `/cards/:id`
- `/cards/:id/invoice`
- `/analysis`
- `/analysis/spending`
- `/analysis/income`
- `/analysis/categories`
- `/goals`
- `/goals/:id`
- `/insights`
- `/insights/:id`
- `/copilot`
- `/profile`

## 04.4 Financial Components

- Financial Health
- Metric Card
- Balance Card
- Account Card
- Credit Card
- Invoice Card
- Transaction Item
- Goal Card
- Spending Breakdown
- Financial Timeline
- Forecast Card
- Financial Status
- Financial Variation
- Financial Indicator
- Financial Comparison
- Financial Range

## 04.5 Intelligence Components

- Insight Card
- Insight Detail
- AI Response
- AI Suggestion
- Recommendation
- Explanation
- Confidence Indicator
- Simulation Card
- “Como calculamos?”

## Regras

- Contexto antes do detalhe.
- Comparação quando útil.
- Período explícito.
- Estimativas identificadas.
- Não usar cor como único significado.
- Componentes devem ser composáveis.
- IA não deve ganhar tratamento visual que sugira autoridade financeira absoluta.
