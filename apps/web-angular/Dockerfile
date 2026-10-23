# syntax=docker/dockerfile:1.7
# Angular 22 needs Node >= 22.22 → build in node:22, serve static files with unprivileged nginx.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# npm silently skips optional deps whose download fails (npm/cli#4828); verify the native bundler binding
# actually loads and reinstall once if not, so a network blip can never yield a broken build.
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund \
 && (node -e "require('rolldown')" 2>/dev/null || (rm -rf node_modules && npm ci --no-audit --no-fund --prefer-online)) \
 && node -e "require('rolldown'); console.log('rolldown native binding OK')"
COPY . .
RUN npx ng build --configuration production

FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime
COPY --from=build /app/dist/web-angular/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /docker-entrypoint.d/40-streamsphere-config.sh
USER 0
RUN chmod +x /docker-entrypoint.d/40-streamsphere-config.sh
USER 101
ENV API_BASE=http://localhost:8080 OIDC_AUTHORITY=http://localhost:8180/realms/streamsphere OIDC_CLIENT_ID=streamsphere-web
EXPOSE 8080
