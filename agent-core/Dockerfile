# syntax=docker/dockerfile:1.7
# =============================================================================
# TripAgent / agent-core  --  Spring Boot 3.5 (:8080, Java 17)
#
# 构建命令 (build context 必须是仓库根 TripAgent/, 因为 Maven 父 pom 在仓库根):
#   docker build -f agent-core/Dockerfile -t tripagent/agent-core:latest .
#
# 安全红线 (务必遵守, 否则会泄露真实密钥):
#   禁止把本地已构建的 target/agent-core-*.jar 直接 COPY 进镜像.
#   src/main/resources/.env 已被 Maven 打进 BOOT-INF/classes/.env, 本地 jar 里带真密钥.
#   本 Dockerfile 的三重防线:
#     (1) 只在容器内从源码重新构建, 从不 COPY 本地 jar;
#     (2) 根 .dockerignore 已排除 **/.env, 密钥进不了 build context;
#     (3) 构建末尾用 jar tf 自检, 一旦发现 BOOT-INF/classes/.env 立即让构建失败.
# =============================================================================

# ---------------------------- stage 1: build ---------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 1) 先只拷贝 pom 骨架, 把 "下载依赖" 固化成可缓存的一层.
#    注意: 父 pom 的 <modules> 声明了 agent-core 与 mcp-server, 用 -pl 时
#    Maven 仍要解析完整 reactor, 所以两个子模块的 pom.xml 都必须存在.
COPY pom.xml ./
COPY agent-core/pom.xml agent-core/pom.xml
COPY mcp-server/pom.xml mcp-server/pom.xml

# 预热本地仓库; 失败不致命(后面 package 阶段会重新解析), 用 cache mount 跨构建复用
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl agent-core -am dependency:go-offline -DskipTests || true

# 2) 拷贝源码, 在容器内从源码重新构建.
#    .dockerignore 保证这里不会出现 src/main/resources/.env
COPY agent-core/src agent-core/src

# -am 会带上父 pom; -DskipTests 因为单元测试已在 CI 的 backend job 跑过
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl agent-core -am package -DskipTests

# 3) 密钥自检: jar 内若出现 BOOT-INF/classes/.env, 构建直接失败(不放行)
RUN if jar tf agent-core/target/agent-core-*.jar | grep -q 'BOOT-INF/classes/[.]env'; then \
        echo 'FATAL: src/main/resources/.env 被打进了 jar, 拒绝产出镜像(密钥泄露)'; \
        exit 1; \
    fi; \
    echo 'SECURITY-CHECK OK: jar 内不存在 .env'

# --------------------------- stage 2: runtime --------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL org.opencontainers.image.title="tripagent-agent-core" \
      org.opencontainers.image.description="TravelPal agent-core (ReAct / Graph / Multi-Agent) Spring Boot service" \
      org.opencontainers.image.vendor="uno.zhuchen"

# 非 root 运行: 固定 uid/gid=1001, 便于宿主机挂载卷时的权限对齐
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid 1001 --create-home --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# 只从 build 阶段取 fat jar; 通配符只会命中 agent-core-<ver>.jar
# (spring-boot repackage 留下的 .jar.original 不匹配 *.jar)
COPY --from=build --chown=app:app /workspace/agent-core/target/agent-core-*.jar /app/app.jar

USER app

# 密钥不烘进镜像: 运行时用 docker run -e / compose environment 注入
# (DEEPSEEK_API_KEY / DASHSCOPE_API_KEY / MYSQL_* / REDIS_* 等)
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

EXPOSE 8080

# exec 形式的 ENTRYPOINT: java 直接是 PID 1, 能正确接收 SIGTERM 做优雅停机.
# 需要追加 JVM 参数时用 docker run -e JAVA_TOOL_OPTIONS="..." (JVM 原生识别).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
