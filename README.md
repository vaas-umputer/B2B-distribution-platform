# Imperial Distribution — B2B Distribution Platform

A multi-tenant B2B order, inventory, and credit-management platform built as
independently deployable Spring Boot microservices, with PostgreSQL
row-level security for tenant isolation and RabbitMQ for event-driven
credit rotation.

## Live deployment

**[b2b-distribution-platform-gateway.onrender.com](https://b2b-distribution-platform-gateway.onrender.com)**
— live as of **October 21, 2026**.

This is a free-tier deployment (Render web services + Postgres, CloudAMQP
RabbitMQ) meant as a working demo, not a permanent environment — free-tier
services spin down after ~15 minutes of inactivity, so the first request
after idle time can take up to a minute to wake back up. A walkthrough
video is coming soon. A permanent deployment with real object storage for
documents (see [Object storage](#object-storage-current-state) below) is
planned for later; this URL may change or go offline when that happens.

## Architecture

```
                         ┌─────────────────────┐
  Browser  ───────────▶  │   gateway (Nginx)    │   serves dashboard + reverse-proxies /api/*
                         └──────────┬───────────┘
                                    │
      ┌─────────────────┬──────────┼──────────────┬──────────────────┐
      ▼                 ▼          ▼               ▼                  
┌───────────┐   ┌──────────────┐ ┌────────────┐ ┌────────────┐
│  tenant-  │   │    order-    │ │  credit-   │ │  invoice-  │
│  catalog  │   │  inventory   │ │  service   │ │  service   │
│  :8081    │   │   :8082      │──▶  :8083    │ │   :8084    │
└─────┬─────┘   └──────┬───────┘ └─────┬──────┘ └─────┬──────┘
      │                │ saga orchestrator, Feign      │
      │                └───────────────▶────────────────┘
      │                                │  RabbitMQ events (invoice.paid/overdue)
      ▼                                ▼
┌──────────┐                    ┌─────────────┐
│ tenant_db│  ...                │ credit_db / order_db / invoice_db │
└──────────┘                    └─────────────┘
```

Each service:
- owns its own Postgres database (no cross-service table access),
- is a separately buildable/deployable Docker image,
- talks to its siblings only through typed DTOs (`common` module) over
  synchronous REST/Feign (order→credit, order→invoice) or async RabbitMQ
  events (invoice→credit).

| Service | Port | Responsibility |
|---|---|---|
| `tenant-catalog-service` | 8081 | Tenant onboarding/auth, admin review queue, product catalog, document upload |
| `order-inventory-service` | 8082 | Order placement saga (stock reservation → credit lock → invoice issue), inventory |
| `credit-service` | 8083 | Per-tenant credit limits, shared working-capital pool, risk scoring, admin overrides |
| `invoice-service` | 8084 | Invoice lifecycle (issued/paid/overdue), overdue sweep job |
| `gateway` | 8080 | Nginx — serves the built React dashboard and reverse-proxies `/api/*` by path |

Tenant isolation is enforced by Postgres **row-level security**, not
application code: every tenant-scoped query runs with
`app.current_tenant` set via `@TenantScoped` (see `common`'s
`TenantRlsAspect`), and RLS policies reject anything outside that tenant's
rows at the database layer.

## Tech stack

Java 21 · Spring Boot 3.3 (Web, Security, Data JPA, AMQP, OpenFeign) ·
PostgreSQL 16 (RLS) · RabbitMQ 3 · Flyway · React + Vite · Nginx · Docker /
docker-compose · Gradle (multi-module monorepo)

## Prerequisites

- Docker + Docker Compose
- JDK 21 (only needed to run `./gradlew` outside Docker)

## Quick start

```bash
docker compose up -d --build
```

This builds and starts Postgres, RabbitMQ, all four backend services, and
the gateway. First boot runs Flyway migrations automatically. Open:

```
http://localhost:8080
```

Default admin login: `admin` / `admin-dev-secret-CHANGE-ME` (override via
`ADMIN_USERNAME`/`ADMIN_PASSWORD` env vars before deploying anywhere real).

## Manual test walkthrough

1. **Sign up a dealer** — on the login screen, switch to *Dealer* →
   *Create agent account* → fill in business details → submit. You'll see
   "Signup submitted, pending review."
2. **Approve it** — log in as *Admin* → *Onboarding review* → Approve the
   new business.
3. **Fund its credit** — still as admin, use *Manual credit override*:
   pick the tenant from the dropdown, set a limit, submit. (First-time
   provisioning and later overrides both go through this one form.)
4. **Place an order** — log out, log back in as the dealer → pick a
   product and quantity → *Place order*. It should complete and an invoice
   should appear.
5. **Check isolation** — try hitting another tenant's `/catalog/{id}` or
   `/orders/{id}` with this dealer's token; both should be rejected (RLS +
   path-ownership checks).

## Running tests

```bash
./gradlew build
```

Integration tests spin up their own ephemeral Postgres/RabbitMQ via
Testcontainers — no local services required, just a working Docker daemon.

## Object storage (current state)

Onboarding document uploads (`POST /documents`, `tenant-catalog-service`)
currently write to **local disk** on the container (`uploads/` by default,
configurable via `DOCUMENT_STORAGE_LOCAL_PATH`) — the database only ever
stores the file's metadata and a storage key, never the bytes themselves,
exactly as if it were already object storage.

This works for local `docker-compose` (the volume persists on your
machine) but **not** on the live Render deployment: Render's free web
services have an ephemeral filesystem, so anything written to local disk
is lost on every restart, redeploy, or free-tier spin-down/spin-up cycle.
Uploaded documents on the live demo should be assumed temporary.

The fix is already anticipated in the code (`DocumentController`'s own
comment: "replace its file write with an R2 putObject call") — swapping
in real object storage (Cloudflare R2, S3, etc.) behind the same
`storage_key` column is a contained change, planned alongside the move to
a permanent deployment.

## Project layout

```
common/                    shared DTOs, JWT, RLS aspect, idempotency guard
tenant-catalog-service/
order-inventory-service/
credit-service/
invoice-service/
dashboard/                 React + Vite frontend
gateway/                   Nginx config + Dockerfile
infra/postgres-init/       creates app_user/svc_system roles + per-service databases
.github/workflows/ci.yml   Gradle build on every push
```
