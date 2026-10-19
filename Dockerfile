FROM eclipse-temurin:17-jre

LABEL maintainer="Echo Mock Server"
LABEL description="Enterprise Mock Server for HTTP and JMS"

WORKDIR /app

# HEALTHCHECK uses curl; install only the runtime package and remove apt metadata.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root user
RUN useradd -m -s /bin/bash echo

# Copy JAR
COPY build/libs/echo-server-*.jar app.jar

# Data directory
RUN mkdir -p /app/data /app/backups && chown -R echo:echo /app

USER echo

# Environment variables
ENV JAVA_OPTS="-Xms256m -Xmx512m \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:+ExitOnOutOfMemoryError \
    -XX:HeapDumpPath=/tmp/heapdump.hprof \
    -Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=5,filesize=10m" \
    TZ=Asia/Taipei \
    ECHO_HEALTH_STORAGE_PATH=/app/data

EXPOSE 8080 61616

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD curl --fail --silent --show-error --header 'Accept: application/json' \
        http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
