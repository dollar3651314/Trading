# quote-app（报价服务）镜像，包名 trading-quote（第 4 批，部署方案 v1 02 页）。先打包 jar，再在仓库根目录构建：
#   mvn -B -ntp package -DskipTests
#   docker build -f deploy/quote.Dockerfile --build-arg FX_VERSION=<完整提交号> -t trading-quote:<提交号> .
# 镜像里不放任何密钥（内部 Key、加密 Key、报价源 token 都在服务器 .env）。容器不映射任何端口，只在 Docker 内部网络 quote 上监听（见 docker-compose.yml）。
FROM eclipse-temurin:25-jre-noble
ARG FX_VERSION=dev
LABEL org.opencontainers.image.source="https://github.com/dollar3651314/Trading" \
      org.opencontainers.image.revision="${FX_VERSION}"
# 内存：这里的 JAVA_TOOL_OPTIONS 只是不经过部署工具单独运行时的保底；服务器上由 deploy/kit/docker-compose.yml 按 .env 的 TR_HEAP_*、TR_MEM_* 显式覆盖
# （容器 mem_limit = memswap_limit，超出就退出、由 Docker 重启，不靠交换分区撑着）
ENV FX_VERSION=${FX_VERSION} \
    TZ=UTC \
    MALLOC_ARENA_MAX=2 \
    JAVA_TOOL_OPTIONS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
RUN useradd --system --uid 10002 --home /app quote && mkdir -p /app && chown -R quote /app
COPY quote-app/target/quote-app-*-exec.jar /app/quote.jar
USER quote
WORKDIR /app
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/quote.jar"]
