# syntax=docker/dockerfile:1.6
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml .
# Warm the dependency layer before copying source so edits don't refetch jars.
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 labroute
WORKDIR /app
COPY --from=build /src/target/labroute-1.0.0.jar ./labroute.jar
USER labroute
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=6 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/api/health || exit 1"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/labroute.jar"]
