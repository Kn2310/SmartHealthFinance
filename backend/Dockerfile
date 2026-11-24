# syntax=docker/dockerfile:1.7
# Imagem da API (specs 05.11: Docker multi-stage). Usada em CI, staging e produção.
# Build (em backend/): docker build -t smart-health-finance-api:<sha> .
# Testes NÃO rodam aqui: o pipeline de CI executa unit/architecture/integration antes do build da imagem.

# ---------- build ----------
# Bases fixadas por digest (ADR-0044 §3); atualizar só por PR revisado. Tags: 25-jdk e 25-jre.
FROM eclipse-temurin:25-jdk@sha256:b8705e936964c228dfce359e63dbf08f6c69cb380ed1d576b1f6b1064f15567d AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
	&& cp target/smart-health-finance-api-*.jar app.jar \
	&& java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------- runtime ----------
FROM eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699 AS runtime

RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app

# Camadas da menos para a mais volátil: melhora cache de build e de pull.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app

ENV TZ=UTC \
	JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# 8080: API. 8081: actuator quando MANAGEMENT_SERVER_PORT=8081 (não publicado pelo proxy).
# Healthcheck no compose (ADR-0044 §3): a porta 8081 só existe quando o ambiente a define.
EXPOSE 8080 8081

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
