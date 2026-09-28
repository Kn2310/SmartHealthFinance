# syntax=docker/dockerfile:1.7
# Imagem da API (specs 05.11: Docker multi-stage). Usada em CI, staging e produção.
# Build (em backend/): docker build -t smart-health-finance-api:<sha> .
# Testes NÃO rodam aqui: o pipeline de CI executa unit/architecture/integration antes do build da imagem.

# ---------- build ----------
FROM eclipse-temurin:25-jdk AS build
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
FROM eclipse-temurin:25-jre AS runtime

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
EXPOSE 8080 8081

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
