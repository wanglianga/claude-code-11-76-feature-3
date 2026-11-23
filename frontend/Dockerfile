# syntax=docker/dockerfile:1.6
# ---- build stage ----
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---- runtime stage: nginx serves the SPA and reverse-proxies /api ----
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --retries=6 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1/ >/dev/null || exit 1"]
