-- Insights de Cartões na insights-v2 (ADR-0028 §7): o tipo CARDS passa a ser aceito. Só aditiva; os códigos novos de
-- detector e ação cabem nas colunas de texto existentes.
alter table insights drop constraint ck_insights_type;
alter table insights add constraint ck_insights_type check (type in ('SPENDING', 'INCOME', 'HEALTH', 'CARDS'));
