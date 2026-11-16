# syntax=docker/dockerfile:1.7
# Imagem do frontend (Next.js 16, BFF) — ADR-0044 §3. Usada em staging (e produção no M14).
# Contexto de build = RAIZ do repositório, porque o prebuild lê os tokens e fontes de design/:
#   docker build -f frontend/Dockerfile -t smart-health-finance-web:<sha> .
# Nenhuma variável sensível entra no build: APP_BASE_URL, OIDC_*, API_BASE_URL e SESSION_SECRET são lidas em
# runtime (src/server/env.ts valida na subida). Testes NÃO rodam aqui: o CI os executa antes do build da imagem.

# node:22-alpine (digest fixado; atualizar só por PR revisado).
ARG NODE_IMAGE=node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a

# ---------- dependências ----------
FROM ${NODE_IMAGE} AS deps
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
WORKDIR /repo/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile

# ---------- build ----------
FROM ${NODE_IMAGE} AS build
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0 \
	NEXT_TELEMETRY_DISABLED=1 \
	NODE_ENV=production
WORKDIR /repo
COPY design/ design/
COPY frontend/ frontend/
COPY --from=deps /repo/frontend/node_modules frontend/node_modules
WORKDIR /repo/frontend
RUN corepack enable && pnpm build

# ---------- runtime ----------
FROM ${NODE_IMAGE} AS runtime
ENV NODE_ENV=production \
	NEXT_TELEMETRY_DISABLED=1 \
	HOSTNAME=0.0.0.0 \
	PORT=3000 \
	TZ=UTC
WORKDIR /app

# Usuário sem privilégio e sem shell de login; os arquivos do app pertencem a root (somente leitura para o app).
# O runtime só precisa do `node`: npm, corepack e yarn da imagem base saem (menos superfície e menos CVEs).
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app \
	&& rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-* \
		/usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg

COPY --from=build /repo/frontend/.next/standalone ./
COPY --from=build /repo/frontend/.next/static ./.next/static
COPY --from=build /repo/frontend/public ./public

USER app
EXPOSE 3000

# Healthcheck sem ferramenta extra: o próprio node faz o GET (o compose também o declara).
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
	CMD ["node", "-e", "fetch('http://127.0.0.1:3000/auth/signed-out').then(r=>process.exit(r.status<500?0:1)).catch(()=>process.exit(1))"]

CMD ["node", "server.js"]
