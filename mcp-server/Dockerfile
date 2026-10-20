# syntax=docker/dockerfile:1.7
# =============================================================================
# TripAgent / mcp-server  --  MCP 工具服务 (:8081, Java 17)
#
# 构建命令 (build context 必须是仓库根 TripAgent/):
#   docker build -f mcp-server/Dockerfile -t tripagent/mcp-server:latest .
#
# 安全红线: 同 agent-core -- 本地 target/mcp-server-*.jar 内含
# BOOT-INF/classes/.env (实测 985 字节真密钥), 绝不允许 COPY 进镜像.
# 三重防线: 容器内源码重建 + 根 .dockerignore 排除 .env + jar 内自检.
# =============================================================================

# ---------------------------- stage 1: build ---------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 1) pom 骨架先行, 让依赖层可缓存(父 pom 的 <modules> 需要两个子模块 pom 都在场)
COPY pom.xml ./
COPY agent-core/pom.xml agent-core/pom.xml
COPY mcp-server/pom.xml mcp-server/pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl mcp-server -am dependency:go-offline -DskipTests || true

# 2) 源码进容器, 在容器内重建(.dockerignore 保证不含 src/main/resources/.env)
COPY mcp-server/src mcp-server/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl mcp-server -am package -DskipTests

# 3) 密钥自检
RUN if jar tf mcp-server/target/mcp-server-*.jar | grep -q 'BOOT-INF/classes/[.]env'; then \
        echo 'FATAL: src/main/resources/.env 被打进了 jar, 拒绝产出镜像(密钥泄露)'; \
        exit 1; \
    fi; \
    echo 'SECURITY-CHECK OK: jar 内不存在 .env'

# --------------------------- stage 2: runtime --------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL org.opencontainers.image.title="tripagent-mcp-server" \
      org.opencontainers.image.description="TravelPal mcp-server (amap / 12306 / cloud search / RAG) tool service" \
      org.opencontainers.image.vendor="uno.zhuchen"

RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid 1001 --create-home --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build --chown=app:app /workspace/mcp-server/target/mcp-server-*.jar /app/app.jar

USER app

# 密钥运行时注入: AMAP_API_KEY / BAIDU_API_KEY / TAVILY_API_KEY / DASHSCOPE_API_KEY / MYSQL_* / MILVUS_*
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

EXPOSE 8081

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
