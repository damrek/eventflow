# Two runtime targets, one build:
#   runtime-proxy  Compose: nginx serves the SPA and proxies /api/* to the services by name.
#   runtime-static k3d: nginx serves only the SPA; the Ingress routes and rewrites the API paths,
#                  so nginx has no upstream hostname to resolve at start-up.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine AS runtime-proxy
COPY --from=build /app/dist /usr/share/nginx/html
# The official image renders /etc/nginx/templates/*.template through envsubst at start-up using
# the environment variables below, which is how the same image works in any Compose project.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
ENV CATALOG_UPSTREAM=catalog:8081 \
    BOOKING_UPSTREAM=booking:8082 \
    NOTIFICATION_UPSTREAM=notification:8083
EXPOSE 80

FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime-static
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx/static.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
