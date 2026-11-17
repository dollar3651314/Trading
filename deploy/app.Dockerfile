# trading-app 镜像（第 4 批，部署方案 v1 02 页）。先打包 jar（CI 的 image job 里做，测试已在 backend、e2e job 跑过），再在仓库根目录构建：
#   mvn -B -ntp package -DskipTests
#   docker build -f deploy/app.Dockerfile --build-arg FX_VERSION=<完整提交号> -t trading-app:<提交号> .
# 镜像里不放任何密钥；数据库、Redis、TopUP 等配置在运行时由 deploy/kit/docker-compose.yml 和服务器的 .env 传入。
FROM eclipse-temurin:25-jre-noble
ARG FX_VERSION=dev
LABEL org.opencontainers.image.source="https://github.com/dollar3651314/Trading" \
      org.opencontainers.image.revision="${FX_VERSION}"
# FX_VERSION：健康检查返回的 version（部署脚本据此确认新版本已生效）
# 内存：这里的 JAVA_TOOL_OPTIONS 只是不经过部署工具单独运行时的保底；服务器上由 deploy/kit/docker-compose.yml 按 .env 的 TR_HEAP_*、TR_MEM_* 显式覆盖
# （容器 mem_limit = memswap_limit，超出就退出、由 Docker 重启，不靠交换分区撑着）
ENV FX_VERSION=${FX_VERSION} \
    FX_STORAGE_DIR=/data/storage \
    TZ=UTC \
    MALLOC_ARENA_MAX=2 \
    JAVA_TOOL_OPTIONS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
RUN useradd --system --uid 10001 --home /app trading && mkdir -p /app /data/storage && chown -R trading /app /data
COPY backend/target/trading-app-*.jar /app/app.jar
USER trading
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
