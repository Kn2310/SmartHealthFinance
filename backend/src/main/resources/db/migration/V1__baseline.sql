-- Baseline do schema do Smart Health Finance.
-- Tabelas de negócio entram com suas vertical slices (M1+), sempre via migrations versionadas
-- seguindo expand/contract. Dinheiro sempre em NUMERIC, nunca em float/double.

COMMENT ON SCHEMA public IS 'Smart Health Finance — source of truth (PostgreSQL)';
