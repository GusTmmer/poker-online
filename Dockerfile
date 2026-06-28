# ── Frontend build ──────────────────────────────────────────────────────────
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ── Server build ────────────────────────────────────────────────────────────
FROM gradle:8.5-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle :server-gcp:installDist --no-daemon

# ── Runtime ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/server-gcp/build/install/server-gcp ./
# Serve the built SPA same-origin with the API (no CORS, no cross-site cookie).
COPY --from=frontend /app/frontend/dist ./static
ENV STATIC_DIR=/app/static

EXPOSE 8080

CMD ["./bin/server-gcp"]
