# camunda-eval: the rules UI and its service in one image.
# 1. the frontend is built with node into backend/build/resources/static (vite's outDir)
# 2. the service jar is built with gradle, picking those files up as static resources
FROM node:22-bookworm-slim AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npx vite build

FROM eclipse-temurin:17-jdk AS backend
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY evaluator evaluator
COPY evaluator-test evaluator-test
COPY backend/build.gradle backend/build.gradle
COPY backend/src backend/src
COPY --from=frontend /src/backend/build/resources/static backend/build/resources/static
# the frontend is already built: skip the npm step the local build runs
RUN ./gradlew :backend:bootJar -x buildFrontend -x test --no-daemon -q

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=backend /src/backend/build/libs/backend-1.0-SNAPSHOT.jar /app/rules.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
VOLUME /data
EXPOSE 7070
ENTRYPOINT ["/app/entrypoint.sh"]
