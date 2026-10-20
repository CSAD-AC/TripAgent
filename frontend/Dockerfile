# syntax=docker/dockerfile:1.7
# =============================================================================
# TripAgent / frontend  --  React 19 + Vite 8 构建, nginx 托管 (:80)
#
# 构建命令 (build context 为仓库根 TripAgent/):
#   docker build -f frontend/Dockerfile -t tripagent/frontend:latest .
#
# 说明:
#   - 前端产物 dist/ 一律在容器内 npm ci + vite build 生成, 不 COPY 本地 dist/;
#   - nginx.conf 以独立文件复制进镜像 (SPA 回退 + /api 反代到 agent-core:8080 + SSE 关缓冲);
#   - 反代目标用 Compose 服务名 agent-core, 所以 nginx 启动时该主机名必须可解析.
# =============================================================================

# ---------------------------- stage 1: build ---------------------------------
FROM node:22-alpine AS build
WORKDIR /app

ENV CI=true

# 1) 先只拷 lockfile, 让 npm 依赖层可缓存
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

# 2) 拷前端源码(node_modules/dist 已被 .dockerignore 排除), 执行 tsc + vite build
COPY frontend/ ./
RUN npm run build && test -f /app/dist/index.html

# --------------------------- stage 2: runtime --------------------------------
FROM nginx:1.27-alpine AS runtime

LABEL org.opencontainers.image.title="tripagent-frontend" \
      org.opencontainers.image.description="TravelPal web UI (React 19 + Vite 8) served by nginx" \
      org.opencontainers.image.vendor="uno.zhuchen"

# 默认站点配置与默认欢迎页都不要
RUN rm -f /etc/nginx/conf.d/default.conf /usr/share/nginx/html/index.html /usr/share/nginx/html/50x.html

# 站点配置以独立文件形式进镜像
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

# 只带静态产物, 不带源码与 node_modules
COPY --from=build /app/dist /usr/share/nginx/html

# 反代失败时的友好提示页(error_page 指向它, 用静态文件避免内部重定向环)
RUN <<'EOF' cat > /usr/share/nginx/html/50x.html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TravelPal - 服务暂时不可用</title>
  <style>
    html, body { margin: 0; height: 100%; background: #F5F0EB; color: #2B2B2B; }
    body { display: flex; align-items: center; justify-content: center;
           font-family: Inter, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
    .card { max-width: 460px; padding: 40px 44px; background: #FFFDFB; border-radius: 14px;
            box-shadow: 0 8px 28px rgba(43, 43, 43, .08); border-top: 4px solid #C6663E; }
    h1 { margin: 0 0 12px; font-size: 22px; font-family: "Playfair Display", Georgia, serif; }
    p  { margin: 0; line-height: 1.7; color: #5B554F; font-size: 15px; }
    code { background: #EDE4DA; padding: 2px 6px; border-radius: 4px; font-size: 13px; }
  </style>
</head>
<body>
  <div class="card">
    <h1>后端服务暂时不可用</h1>
    <p>agent-core (<code>8080</code>) 未就绪或正在重启, 请稍后刷新重试.</p>
  </div>
</body>
</html>
EOF

EXPOSE 80

# 官方 nginx 镜像入口会用 exec 拉起 nginx (master 为 PID 1), 保持默认 CMD 即可
CMD ["nginx", "-g", "daemon off;"]
