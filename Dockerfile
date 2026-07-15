# ---- Build stage: compile the Spring Boot jar ----
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ---- Runtime stage: JRE + Redis, both from the same Alpine base ----
FROM eclipse-temurin:25-jre-alpine

# Installed from Alpine's own package repo (not copied from a differently-based
# image, not compiled from source) so the redis-server binary is guaranteed to
# match this image's musl libc build.
RUN apk add --no-cache redis \
    && addgroup -S plaza \
    && adduser -S -G plaza plaza \
    && mkdir -p /data \
    && chown plaza:plaza /data

COPY docker/redis.conf /etc/redis/redis.conf
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

# Redis persistence (RDB snapshots per docker/redis.conf's `save` policy) lives
# here; bind-mount or a named volume on /data to survive container recreation.
VOLUME ["/data"]
EXPOSE 8080

USER plaza
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
