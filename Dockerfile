FROM eclipse-temurin:17-jre

LABEL maintainer="Echo Mock Server"
LABEL description="Enterprise Mock Server for HTTP and JMS"

WORKDIR /app

# 建立非 root 用戶
RUN useradd -m -s /bin/bash echo

# 複製 JAR
COPY build/libs/echo-server-*.jar app.jar

# 資料目錄
RUN mkdir -p /app/data && chown -R echo:echo /app

USER echo

# 環境變數
ENV JAVA_OPTS="-Xms256m -Xmx512m \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/tmp/heapdump.hprof \
    -Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=5,filesize=10m" \
    TZ=Asia/Taipei

EXPOSE 8080 61616

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
    CMD curl -f http://localhost:8080/mock/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
