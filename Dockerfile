# One image: the Next.js client is exported to static files and served by Spring Boot (same origin).

FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS server
WORKDIR /server
COPY server/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY server/src ./src
COPY --from=web /web/out ./src/main/resources/static
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 nova
COPY --from=server /server/target/nova-server-*.jar /app/nova.jar
USER nova
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/nova.jar"]
