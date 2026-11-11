# One image: the Spring Boot backend serving the built React frontend.
#   docker build -t idea-generator .
# Runtime config is environment only (see docker-compose.yml and docs/MULTI-USER.md).

# ---- frontend
FROM node:22-alpine AS web
WORKDIR /web
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

# ---- backend
FROM eclipse-temurin:25-jdk-noble AS app
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B package -DskipTests && cp target/idea-generator-*.jar /app.jar

# ---- runtime
FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data ideagen && mkdir -p /data && chown ideagen /data
COPY --from=app /app.jar /app/app.jar
COPY --from=web /web/dist /app/static
USER ideagen
WORKDIR /data
ENV HOST=0.0.0.0 \
    PORT=8300 \
    IDEAGEN_AUTH=oauth \
    IDEAGEN_DATA_DIR=/data \
    SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/static/ \
    SPRING_DOCKER_COMPOSE_ENABLED=false
EXPOSE 8300
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8300"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
