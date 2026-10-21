FROM node:22-alpine AS build
WORKDIR /app
COPY dashboard/package.json ./
RUN npm install
COPY dashboard/ ./
RUN npm run build

FROM nginx:1.27-alpine
# Defaults match docker-compose's service names/ports - override these on
# a host like Render by setting the same-named env vars to each deployed
# service's public URL (see README's deployment section). nginx's own
# docker-entrypoint.d/20-envsubst-on-templates.sh envsubst's this template
# into /etc/nginx/conf.d/default.conf on container start; the restrictive
# filter keeps it from also mangling nginx's OWN $uri/$1-style variables
# in the config, which plain unrestricted envsubst would blank out.
ENV TENANT_CATALOG_URL=http://tenant-catalog-service:8081 \
    ORDERS_URL=http://order-inventory-service:8082 \
    CREDIT_URL=http://credit-service:8083 \
    INVOICES_URL=http://invoice-service:8084 \
    RESOLVER=127.0.0.11 \
    NGINX_ENVSUBST_FILTER=^(TENANT_CATALOG_URL|ORDERS_URL|CREDIT_URL|INVOICES_URL|RESOLVER)$
COPY gateway/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
