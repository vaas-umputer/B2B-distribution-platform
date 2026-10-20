# B2B Distribution Platform — Phased Plan

Oct 20, 2026 · @Sanky

A Spring Boot + PostgreSQL/MongoDB multi-tenant platform modeling Imperial as a virtual-wholesaler distributor: dealer-network agents are onboarded as isolated tenants, each extended a tier-based credit line enforced at order time against a shared working-capital pool. v1 covers the core transactional platform; v2 (invoice discounting) is designed but deliberately not built now.

## v1 Scope & Features

| Feature | What it does |
| --- | --- |
| Tenant onboarding | Agents onboarded as isolated tenants; an onboarding Credit Risk Score drives a suggested limit |
| Row-level tenant isolation | PostgreSQL RLS enforces tenant\_id at the DB layer, not in application code |
| Shared catalog, tenant pricing | Product catalog in MongoDB; region-specific pricing layered on top |
| Credit limit per tenant | Declared volume × risk multiplier, admin-reviewed (see Credit Algorithm) |
| Working-capital pool check | Order-time check against Imperial's shared available capital, not just the tenant's own limit |
| Inventory check-then-commit | No order confirmed without a reserved, available stock check |
| Invoice lifecycle | ISSUED to PAID/OVERDUE state machine, driving credit release or throttling |
| Dealer dashboard | Order placement and status, tenant-scoped |
| Admin dashboard | Cross-tenant visibility with an auditable access boundary |
| Tenant-private client log | Optional, within a tenant's own space — not platform-enforced credit |

### Explicit v1 non-goals

- Invoice discounting / financier marketplace (v2, below)
- Multi-currency
- Full RBAC permission editor
- Real payment gateway integration

## Entity Hierarchy

&#91;embedded content: entity hierarchy · Platform → Tenant → Client\]

The real B2B credit line is Imperial → Tenant (the agent you onboard). What a tenant does with their own downstream clients happens outside the platform; v1 optionally lets a tenant log their own clients for private record-keeping, with no platform-side credit enforcement at that level.

## Credit & Working Capital Algorithm

Without invoice discounting, Imperial self-funds every receivable, so credit limits draw from one shared, finite pool rather than existing independently per tenant.

### Level 1 — platform working capital pool

```latex
AvailableWorkingCapital = Cash - CommittedToSupplierPayables - OperatingBuffer
```

A flat **Safety Buffer** is held back from this pool, not allocated to any tenant:

```latex
AllocatablePool = AvailableWorkingCapital \times (1 - BufferPercent)
```

`BufferPercent` ≈ 15%. This buffer exists to absorb new-agent onboarding and approved limit-increase requests (below) without needing to touch any existing agent's limit — a flat reserve held at the pool level, nothing tier-specific, nothing requiring smoothing logic.

### Level 2 — Credit Limit

No tier in v1 — it added a pricing/catalog concept with no actual differentiated logic behind it yet, and kept re-entangling with credit. Credit sizing stands alone.

An onboarding rubric produces a **Credit Risk Score** — explicitly an admin-facing recommendation, never a binding number:

| Factor | Points |
| --- | --- |
| Declared monthly volume | 0–3 |
| Business vintage (years operating) | 0–2 |
| Collateral / security deposit offered | 0–3 |
| Relationship / priority category | 0–2 |

```latex
SuggestedCreditLimit = DeclaredMonthlyVolume \times RiskMultiplier(Score)
```

`RiskMultiplier` bands 0.5× (low score) to 2.0× (high score). The admin sees the suggestion and the score breakdown, and sets the actual `CreditLimit` — accepting, reducing, or raising it, always with a reason logged.

**Why this is practical:**

- A **New-Agent Ceiling** (flat, e.g. ₹15L) bounds every new agent's limit regardless of what the formula suggests, until they've built verified transaction history — guards against an inflated declared volume.
- New agents and approved limit-increase requests draw from the Level 1 **Safety Buffer**, not from any other agent's allocation.
- The Level 1 pool check (`PoolAvailable`) is still the hard, real-time backstop — a wrong individual number can misallocate the pool, never breach total available capital.
- Nothing is auto-committed: the score is a recommendation, the admin decides, every decision is logged.

**Limit-increase requests** (self-service, detailed in Operational Workflow below) are reviewed the same way — against the agent's own utilization, payment history, and the Safety Buffer's remaining headroom, never against a tier.

### Order-time enforcement (the "rotation")

```latex
TenantAvailable = TenantLimit - CurrentOutstandingInvoices(tenant)
```

```latex
PoolAvailable = AllocatablePool - \sum CurrentOutstandingInvoices(\text{all tenants})
```

```latex
OrderAllowed = OrderValue \le \min(TenantAvailable,\ PoolAvailable)
```

Both checks matter: a tenant can be within their own limit while the platform-wide pool is still the binding constraint. Two tenants placing orders simultaneously can both read "pool available" as sufficient and jointly overdraw it — a genuine concurrency problem, handled with a DB-level lock or an optimistic-concurrency check (version column + retry) on the pool balance, the same pattern used for inventory oversell prevention.

**Release:** when an invoice is marked PAID, both `TenantAvailable` and `PoolAvailable` are released back — this is the actual rotation of cash from one paid receivable into capacity for the next order, from any tenant.

**Throttling:** an invoice crossing its due date auto-throttles that tenant (blocks new orders or shrinks effective limit) until cleared.

**Periodic re-evaluation:** a scheduled job (monthly/quarterly) recomputes `PaymentBehaviorMultiplier` and `TenantLimit` from the trailing window — a credit review cycle, and a clean seam for a future ML-based default-risk model (noted as Phase 2, not built in v1).

### Operational Workflow (v1) — system suggests, admin decides

Every decision in this layer follows the same shape: the system computes a suggestion or raises a flag, a human decides, and the decision is logged.

- **Near-limit alert** — flagged on the admin dashboard when `Utilization = CurrentOutstanding / CurrentLimit ≥ 90%`. No automatic action, just visibility at the moment it matters.
- **Monthly review report** — a scheduled job compiles, for admin reading: tenants who hit the alert threshold, tenants well under their limit, and overall pool utilization trend. It changes nothing by itself.
- **Manual override** — admin can adjust any tenant's limit at any time: `overrideReason`, `setBy`, `setAt` logged every time.
- **Agent self-service limit-increase request** — agent submits `requestedAmount` + `reason`; it lands in an admin review queue shown alongside that tenant's utilization and payment history; admin approves (becomes a manual override) or rejects, agent notified either way.

Onboarding/offboarding no longer cascades into other agents' numbers at all — since Credit Limit is now personalized per agent (`DeclaredVolume × RiskMultiplier`) rather than divided from a shared tier budget, one agent joining or leaving a tier simply doesn't touch anyone else's number. The alert + manual review layer above is what replaces the token-bucket/hysteresis/EMA smoothing considered earlier — appropriate at v1's scale, where an admin already reviews everything manually.

## Core Flows

&#91;embedded content: core flow · order to invoice to payment/overdue\]

1. **Order placed** — tenant submits an order.
2. **Credit + pool check** — both `TenantAvailable` and `PoolAvailable` must cover the order value; on pass, the amount is locked (not yet deducted permanently).
3. **Stock check** — inventory is reserved only if available; a failed check blocks the order and releases the locked credit.
4. **Ship + invoice** — order ships, an invoice is issued with payment terms (NET 15/30/60).
5. **Resolution** — paid on terms releases the locked credit back to both tenant and pool; crossing the due date marks the invoice OVERDUE and throttles that tenant's new orders until cleared.

## Microservice Architecture (v1)

| Service | Owns | Database |
| --- | --- | --- |
| Tenant & Catalog Service | Onboarding, RLS policy enforcement, shared product catalog, tenant/region pricing rules | PostgreSQL + MongoDB |
| Order & Inventory Service | Order lifecycle, stock check-then-commit reservation, calls Credit before confirming | PostgreSQL |
| Credit & Working Capital Service | Pool balance, per-tenant limits, the two-level check above | PostgreSQL |
| Invoice Service | Invoice state machine (ISSUED → PAID/OVERDUE → CLOSED), emits events Credit Service reacts to | PostgreSQL |

Order & Inventory Service calls Credit & Working Capital Service synchronously at order time (the two-level check); the stock check-then-commit now happens in-process within that same service rather than as a separate call. Invoice Service's PAID/OVERDUE events feed back into Credit Service to release or throttle — keeping the "finite shared resource under concurrent access" logic in one place instead of leaking into Order & Inventory Service.

## V2 (Deferred): Invoice Discounting

Not built in v1 — designed as an extension point so Invoice Service's event contract doesn't need to change later.

Imperial is the **seller**, so this is seller-side invoice discounting/factoring (not reverse factoring): Imperial holds a receivable from a tenant and sells/discounts it to a financier (NBFC) for early cash, and the financier takes on collection at maturity.

**Planned Invoice Discounting Service states:** `LISTED` (seller lists an ISSUED invoice) → `BID/ACCEPTED` (financier offers, Imperial accepts) → `FUNDED` (Imperial gets cash now, credit released early; financier now owns the receivable) → `CLOSED` (tenant eventually pays the financier).

**Why deferred:** multi-party, regulated-adjacent, and genuinely complex — attempting it alongside v1 risks an unfinishable project. v1's job is to prove the credit/pool mechanics are correct and auditable; v2 extends the same Invoice Service state machine rather than reworking it.

## Reference Implementations

| Repo | Use it for |
| --- | --- |
| [shashankkhasare/effective-fiesta](https://github.com/shashankkhasare/effective-fiesta) | Working Spring Boot + PostgreSQL RLS example with a tenant-aware data source |
| [OpenElementsLabs/spring-services](https://github.com/OpenElementsLabs/spring-services) | Reference for making tenant-safety structural (fail-closed JPA guard) rather than query-by-query |
| [BrianGator/Inventory-Management-Java-Spring-Boot-REST-API](https://github.com/BrianGator/Inventory-Management-Java-Spring-Boot-REST-API) | Closest existing match to the Postgres + MongoDB split (swap MySQL → Postgres) |
| [rishiraj88/order-manager](https://github.com/rishiraj88/order-manager) | Order/inventory service split with stock-sufficiency check before order confirmation |

Treat these as implementation references, not portfolio-recognition risks — none are large-star, widely-recognized repos.

## v1 Practical Constraints & Integration Adapters

### Recalculation trigger

Credit Limit is personalized per agent (`DeclaredVolume × RiskMultiplier`), not divided from a shared tier budget — so a new agent onboarding or an existing one leaving never touches anyone else's number. The only live trigger is the near-limit alert (Operational Workflow, above); everything else is a human decision at the monthly review or on request.

**Safeguard:** a recompute that would shrink a limit must never cut it below the agent's current outstanding exposure.

```latex
NewLimit = \max(RecomputedValue,\ CurrentOutstanding)
```

### Inventory — manual now, adapter-ready for later

v1 stock checks are manual: the admin enters daily stock levels after contacting the relevant team. Built behind an `InventoryGateway` interface so Order Service calls the interface, not the implementation:

- v1: `ManualInventoryAdapter` — admin daily-entry form
- v2: a real ERP/WMS integration, swapped in as a new adapter with no change to Order Service

### Invoicing — PO via agent, Tally stays manual in v1

The PO is submitted through the platform by the agent (replacing WhatsApp/email as the *data-entry* step, not necessarily the relationship — agents can still message too). This is required, not just convenient: Credit and Inventory checks both need structured order data to run.

v1 flow, matching the real process today:

1. Agent submits PO via platform → Order Service runs credit + pool check
2. Admin manually validates stock and confirms
3. Admin generates the invoice in Tally, as today
4. Admin enters the Tally invoice reference back into the platform → Invoice Service records it `ISSUED`, keeping the state machine intact even though generation stayed manual

Same gateway pattern: an `InvoicingGateway` interface, v1 = `ManualTallyEntryAdapter` (admin re-keys the reference), v2 = `TallyIntegrationAdapter` — genuinely buildable later since Tally exposes an XML/ODBC-based API for pushing vouchers and pulling master data.

## Key Decisions & Trade-offs (Brainstorm)

Context fixing the rest of this plan: **solo operator** (you build, run, and debug this alone), **resume/learning project** meant to demonstrate real microservices patterns (not a forced-monolith-is-better argument), stack already decided (Spring Boot, PostgreSQL + MongoDB), and the end goal is something you actually run and improve in phases, not just a diagram.

That changes which trade-offs matter. Below are the decisions considered at each stage, with the option taken and why.

**1. How many services, really?**

- *Option A — the 6 services as planned* (Tenant, Catalog, Order, Credit, Inventory, Invoice): matches the plan, demonstrates real service boundaries, but is the most ops-heavy for one person (6 deployables, 6 log streams, 6 things that can be half-upgraded).
- *Option B — collapse to 3–4*: e.g. merge Inventory into Order (they're called together on every order anyway), merge Catalog into Tenant. Less boundary practice, fewer moving parts.
- *Option C — "modular monolith first, split later"*: one deployable with strict package boundaries (each module owns its own schema/tables, no cross-module repository access), then peel services out once the boundaries have proven stable.
- **Taken: B — 4 services, phased.** Collapsing to **Tenant & Catalog Service**, **Order & Inventory Service**, **Credit & Working Capital Service**, and **Invoice Service** — merging the two pairs that are always called together or share an owner (Catalog's tenant/region pricing belongs next to Tenant's onboarding; Inventory's check-then-commit is invoked by nothing but Order), while keeping Credit and Invoice standing alone, since those two carry this project's actual architectural interest (the shared-pool concurrency logic, and the event-emitting state machine that's also v2's extension point). Built as a true walking skeleton across the 4 real services from day one, not a modular monolith split later — at this size the Docker Compose/networking overhead is small enough that paying it once up front beats writing module boundaries twice (see Phased Implementation Roadmap, Phase 1).

**2. Synchronous vs. event-driven communication**

- *Option A — REST-only, fully synchronous*: simplest to trace and debug (a stack trace / request log tells the whole story), but Invoice→Credit release/throttle either becomes a synchronous call (coupling Invoice to Credit's uptime) or needs polling.
- *Option B — a message broker (RabbitMQ) for the state-change events* (invoice PAID/OVERDUE → Credit release/throttle; limit-increase request → admin notification), REST for everything in the synchronous order-time path.
- *Option C — Kafka*: resume-recognizable, but event sourcing/partitioning/consumer-group concepts are a lot of new surface for a solo debugger; overkill at this data volume.
- *Option D — BullMQ (Redis-backed job queue)*: a solid choice if this stack were Node — but BullMQ is a Node.js/TypeScript library: its job format, delayed-job and rate-limiting logic live in Lua scripts driven from a Node client, and there is no maintained Java client that speaks its protocol. Using it here would mean either running a Node sidecar solely to publish/consume jobs (a second language runtime, for one queue) or hand-rolling the equivalent against raw Redis in Java — which is just reimplementing a chunk of what Spring AMQP already gives you for free. It's also conceptually a *job queue* (one worker claims and completes a job) rather than a *pub/sub broker* (several independent services each get their own copy of an event) — and Invoice→Credit is the latter: Credit Service isn't "doing a background job," it's reacting to a fact Invoice Service announced, a fact other future consumers (a notifications service, v2's Invoice Discounting Service) may also want to see. That's RabbitMQ's fanout/topic exchange model, not a job queue's.
- **Taken: B.** REST (via Spring's `WebClient` or `OpenFeign`) for the synchronous order-time check chain (Order & Inventory Service → Credit Service), RabbitMQ for the asynchronous state-change events. Against Kafka: RabbitMQ is far easier to run and inspect solo (one container, a web UI at `:15672` that shows you every queued message), and is a perfectly legitimate, resume-credible choice at this message volume. Against BullMQ specifically: it doesn't fit the stack (Node-only client, no real Java interop — see Option D) and it solves a different problem (job queue, not pub/sub) than what Invoice→Credit actually is (an event announcement, potentially multi-consumer).

**3. Cross-service transaction consistency**

- *Option A — distributed 2PC/XA transactions*: technically "consistent," but brittle, poorly supported across Postgres+Mongo, and a known anti-pattern at this scale.
- *Option B — choreographed saga* (each service reacts to events from the last, no central coordinator): scales well conceptually, but for one person debugging, failures are scattered across N services with no single place to see "where did this order get stuck."
- *Option C — orchestrated saga* (Order Service owns a state machine for the whole order lifecycle, explicitly calling Credit, then Inventory, then emitting Invoice-creation, and compensating on failure).
- **Taken: C.** One orchestrator you can read top-to-bottom and set a breakpoint in is enormously easier to debug alone than logic smeared across six services. Compensating actions (release locked credit, release reserved stock) are explicit methods on that state machine. See the Saga section below.

**4. Service discovery / API gateway**

- *Option A — Eureka + Spring Cloud Gateway*: the "textbook" Spring Cloud microservices answer, resume-recognizable, but it's two more moving parts to run and debug, and genuinely unnecessary at 6 fixed services that aren't auto-scaling or changing addresses.
- *Option B — fixed service addresses via Docker Compose networking / environment variables, no discovery layer; a thin Spring Cloud Gateway (or even just Nginx) only for routing external traffic to the right service.*
- **Taken: B.** Discovery solves a problem you don't have yet (dynamically changing instance counts/addresses). Add Eureka later only if you actually move to an orchestrator that needs it (see Deployment, Phase 7).

**5. Database-per-service vs. budget reality**

- *Option A — a fully separate managed Postgres instance per service* (5 services use Postgres): architecturally "correct" but materially expensive to run continuously and more infra to provision.
- \*Option B — one Postgres server, one database *per service* (not shared tables) — same isolation and "service owns its schema" discipline, one instance to pay for and back up.
- **Taken: B** for the real/long-running deployment; **Option A conceptually preserved** by never letting two services share a database or query each other's tables directly — the separation that actually matters for the architecture lesson is logical, not which physical server it sits on. (Local dev with Docker Compose gives each service its own Postgres container anyway, since that costs nothing.)

**6. Containers: Docker Compose vs. Kubernetes**

- *Option A — Kubernetes (k3s/minikube) from day one*: resume-impressive, but adds a second large unfamiliar system on top of a first unfamiliar system (microservices), and debugging a crashing pod is a different skill from debugging a crashing JVM process.
- *Option B — Docker Compose for local dev and for the real deployment (via Render, which you already use), Kubernetes deferred to an optional late phase.*
- **Taken: B.** Compose gets you running and debugging distributed services this month. Kubernetes is listed as an optional Phase 7 stretch goal once the services themselves are solid — at that point it's an infrastructure exercise, not a prerequisite for shipping v1.

## Full Tech Stack

| Layer | Choice | Why |
| --- | --- | --- |
| Language / framework | Java 21 LTS + Spring Boot 3.x | Already decided; Spring Boot's ecosystem (Data JPA, Security, Cloud OpenFeign, Validation, Actuator) covers nearly everything below without extra libraries |
| Build | Gradle (multi-module) | One root repo, one module per service (`tenant-service`, `order-service`, …) plus a `common` module for shared DTOs/events — lets you build/test everything with one command while keeping services physically separable |
| Relational DB | PostgreSQL 16 | Already decided; RLS, `SELECT … FOR UPDATE`, and optimistic-locking version columns all used directly below |
| Document DB | MongoDB 7 | Already decided; Catalog Service only |
| Messaging | RabbitMQ 3.x | Async events (invoice state changes, limit-increase notifications); see trade-off #2 above |
| Sync inter-service calls | Spring Cloud OpenFeign | Declarative REST clients — far less boilerplate than hand-rolled `RestTemplate`/`WebClient` calls, and the interface IS the contract |
| API docs | springdoc-openapi (Swagger UI per service) | Auto-generated OpenAPI spec per service from annotations — doubles as the contract reference in Phase 1 |
| Auth | Spring Security + JWT (self-issued, `jjwt` library) | `tenant_id` and `role` as JWT claims, read by a request filter that sets the session-local Postgres variable RLS checks against (`SET app.current_tenant = '<id>'`) — no external IdP needed at this scale |
| Containers | Docker + Docker Compose | Trade-off #6 above — one `docker-compose.yml` brings up all 4 services + Postgres + Mongo + RabbitMQ with one command |
| CI | GitHub Actions | Free for a public repo; runs Gradle build + tests (with Testcontainers, below) on every push |
| Deployment | Render (Docker-based web services) | Already your tool of choice; each service becomes a separate Render service pointed at its own Dockerfile, sharing one Render Postgres instance (separate databases) per trade-off #5 |
| Local observability | Spring Boot Actuator + structured JSON logs to stdout | `/actuator/health`, `/actuator/metrics` per service; Compose's `docker compose logs -f` across all services is enough at this scale — no Prometheus/Grafana until Phase 6 (optional) |
| Testing | JUnit 5 + Testcontainers + REST Assured | Testcontainers spins up real Postgres/Mongo/RabbitMQ in tests — critical for a distributed system, since mocked DBs hide the exact bugs (RLS, locking, event ordering) this architecture is designed to catch |
| Frontend (dashboards) | Plain React + Vite (or server-rendered Thymeleaf if you want zero extra stack) | Not the focus of this plan — pick whichever you already know; the dashboards are thin clients over the APIs |

## Service Communication & Consistency (Orchestrated Saga)

**Synchronous path — order placement.** Order Service is the saga orchestrator. It holds an explicit state machine per order (`PLACED → CREDIT_LOCKED → STOCK_RESERVED → INVOICED → COMPLETED`, with failure states `CREDIT_REJECTED` / `STOCK_REJECTED` and a compensating `ROLLED_BACK`), and calls out synchronously via OpenFeign:

1. `OrderService → CreditService.lockCredit(tenantId, orderValue)` — the one real network call in the chain, since Credit is a separate service. Runs the two-level check (`TenantAvailable`, `PoolAvailable`) atomically inside a single DB transaction with an optimistic-concurrency `version` column on the pool-balance row, retried on conflict. Returns a `lockId`. On failure, order moves to `CREDIT_REJECTED`, saga ends — nothing to compensate yet.
2. `reserveStock(items)` (in-process, within Order & Inventory Service) — check-then-commit reservation, now a direct method call rather than a network hop since Inventory merged into the same service. On failure, order moves to `STOCK_REJECTED`, and the saga calls `CreditService.releaseCredit(lockId)` as the compensating action — this one IS a real network call, back out to Credit.
3. On success of both, Order & Inventory Service creates the order record and calls `InvoiceService.issueInvoice(orderId, terms)` synchronously — this one needs to succeed for the order to be real, so it's part of the synchronous chain, not an event.

**Asynchronous path — invoice lifecycle.** Once ISSUED, Invoice Service owns the invoice state machine independently (it doesn't need Order Service anymore). State transitions publish events to RabbitMQ:

- `invoice.paid` → Credit Service consumes it, releases `TenantAvailable` and `PoolAvailable` for that invoice's locked amount (the "rotation").
- `invoice.overdue` → Credit Service consumes it, throttles that tenant.
- `creditlimit.increase_requested` → a lightweight notification consumer (could just be the Admin dashboard's backend polling a queue, or email) surfaces it in the review queue.

Events are why this is a saga and not just RPC: Credit Service never calls Invoice Service to ask "is this paid yet" — it reacts to what Invoice Service tells it, which is what lets the two evolve independently (and is exactly the seam v2's Invoice Discounting Service plugs into later, per the plan's existing V2 section).

**Idempotency.** Every synchronous call and every consumed event carries an idempotency key (`orderId` + step name for saga calls; the event's own id for consumers) checked against a small `processed_operations` table before acting — required because retries (network blips, consumer redelivery) are normal in this design, and a non-idempotent credit release or stock reservation is the most dangerous kind of bug to have running unattended.

**Concurrency on the shared pool.** As the plan already specifies: `PoolAvailable` reads/writes use either a `SELECT … FOR UPDATE` row lock or an optimistic `version` column with retry-on-conflict. Recommendation: start with the explicit row lock (`FOR UPDATE`) — one line, easy to reason about, and at this transaction volume contention is not a performance concern. Revisit optimistic locking only if you later see lock contention in practice.

## Deployment Architecture

**Local dev (every phase).** One `docker-compose.yml` at the repo root:

```
services:
  postgres:      # one container, one DB per service (tenant_db, order_db, credit_db, invoice_db)
  mongo:         # catalog data, now inside tenant_db's service boundary
  rabbitmq:      # management UI on :15672 for watching queues live
  tenant-catalog-service:
  order-inventory-service:
  credit-service:
  invoice-service:
  gateway:       # Spring Cloud Gateway or Nginx, routes :8080/* to the right service
```

Each service's own `application.yml` points at its own DB by container hostname (`postgres:5432/order_db`, etc.) — no service discovery needed since Compose's internal DNS already resolves service names. `docker compose up` brings up the whole system; `docker compose logs -f order-inventory-service credit-service` is your distributed debugger.

**Real deployment (Render), once the system is solid locally.** Per trade-off #5: one Render-managed Postgres instance, one database per relational service (cheaper than separate managed instances, same logical isolation); one Render-managed MongoDB-compatible store (or a small external Mongo Atlas free tier) for Tenant & Catalog Service's catalog data; one small RabbitMQ instance (Render add-on, or CloudAMQP's free tier which is built for exactly this). Each service deploys as its own Render web service from its own Dockerfile — 4 small services, each independently deployable and restartable, which is also the actual point of the architecture (you can redeploy Invoice Service without touching Order & Inventory Service).

**What's deliberately NOT here in v1:** a service mesh, Kubernetes, Eureka/service discovery, Kafka, Prometheus/Grafana. Each is a legitimate thing to add later (noted as optional Phase 7 stretch work below) once the core system runs and you want the next layer of infrastructure practice — but none of them make the core architecture lesson (service boundaries, async events, saga consistency, tenant isolation) land any better, and all of them add real solo-operator debugging cost today.

## Testing, Observability & CI/CD

**Testing strategy, layered:**

- *Unit tests* per service for the credit math (`RiskMultiplier`, pool arithmetic, throttling thresholds) — pure functions, no DB needed, fast feedback while developing the formulas.
- *Integration tests with Testcontainers* per service — real Postgres/Mongo spun up in the test run, so RLS policies and row-locking are tested against the real engine, not an in-memory fake that would silently pass broken RLS.
- *Saga/contract tests* — the one test suite that matters most for a solo distributed-systems build: spin up Order + Credit + Inventory (via Testcontainers or Compose in CI) and run the full `OrderAllowed` flow, including a forced Inventory failure to verify the Credit compensation actually fires. This is where most "architecture looked right on paper" bugs will surface.
- *Concurrency test* — fire concurrent order requests at the same tenant/pool to confirm the lock (or optimistic retry) actually prevents the overdraw the plan calls out explicitly.

**Observability (v1, right-sized):** structured JSON logs to stdout with a `correlationId` generated at Order Service and passed through every Feign call and event header — this single id is what lets you `grep` one order's whole journey across 4 services' logs, and is worth building on day one since it is the main thing that makes a multi-service bug traceable solo. Actuator health/metrics endpoints per service. Defer Prometheus/Grafana/distributed tracing (Zipkin/Jaeger) to an optional later phase — valuable to learn, not needed to debug at this scale once correlation ids exist.

**CI/CD:** GitHub Actions workflow runs `./gradlew test` (including Testcontainers tests — GH Actions supports Docker-in-Docker natively) on every push; a second job builds and pushes Docker images on merge to main; Render auto-deploys from the image registry or directly from the repo. Keep it this simple until something concrete demands more (e.g. you want blue/green deploys).

## Prep Work Before Writing Code

You're coming from \~1 year mostly Node/Express. These are the real gaps against this plan, roughly in the order you'll hit them:

1. **Spring Boot fundamentals** (if not already comfortable): dependency injection, `@RestController`/`@Service`/`@Repository`, Spring Data JPA basics, `application.yml` profiles. If you already built the effective-fiesta-style reference locally, you likely have enough — verify by building one tiny standalone CRUD service before starting Tenant Service for real.
2. **PostgreSQL Row-Level Security** specifically — `CREATE POLICY`, `SET app.current_tenant`, and how it interacts with a connection pool (HikariCP) that reuses connections across requests (the classic RLS bug: a pooled connection "remembering" the previous request's tenant). Read the shashankkhasare/effective-fiesta repo already in your plan closely for this.
3. **Docker & Docker Compose** — if new to you: images vs. containers, `docker-compose.yml` service/network/volume basics, and specifically multi-container networking (how `order-service` resolves `credit-service:8080` by container name). An hour with the official Docker "Get Started" guide plus writing one 2-service Compose file yourself is usually enough to unblock this.
4. **RabbitMQ basics** — exchanges, queues, bindings, and the publish/consume code in `spring-boot-starter-amqp`. The management UI (`:15672`) is the single best learning aid here — you can watch messages arrive in real time.
5. **Saga pattern / compensating transactions** — conceptually distinct from "just call another service"; worth 30 minutes reading on the orchestration-vs-choreography distinction before writing Order Service's state machine, since that class design is the architectural core of this whole project.
6. **Testcontainers** — a few hours to set up once, pays for itself immediately; the "quickstart" in their docs for Postgres is exactly what you need.
7. **OpenFeign** — small surface area, 15–30 minutes to read; mostly annotations.

Resources:

- [Spring Boot Reference Documentation](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/) — the official docs, genuinely well-organized
- [PostgreSQL Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html) — official docs, read alongside the effective-fiesta repo's actual code
- [RabbitMQ Tutorials](https://www.rabbitmq.com/tutorials) — official, hands-on, language-specific (pick the Spring/Java-AMQP path)
- [Testcontainers for Java — Quickstart](https://testcontainers.com/guides/getting-started-with-testcontainers-for-java/)
- [Microservices.io — Saga pattern](https://microservices.io/patterns/data/saga.html) — Chris Richardson's reference pattern catalog, the clearest write-up of orchestration vs. choreography trade-offs

## Phased Implementation Roadmap

Each phase ends with something you can run and demo, and each is small enough to fully debug before moving on. Order is chosen so integration risk (the hardest part to debug solo) surfaces as early as possible, not last.

**Phase 0 — Foundations (prep work, above)** Gradle multi-module skeleton with `common` module for shared DTOs/events; Docker Compose with just Postgres + Mongo + RabbitMQ running (no services yet); verify RLS + pooled connections behave correctly with a throwaway test table before building real services on top of that assumption.

**Phase 1 — Walking skeleton across real services** Four real Spring Boot apps from day one (Tenant & Catalog, Order & Inventory, Credit, Invoice), wired through Docker Compose and OpenFeign — but each starts as a thin slice: Order & Inventory calls Credit over real HTTP and gets back a hardcoded approval, no real pool math yet. Goal: the full order→credit→stock→invoice path, including a forced-failure compensation case, works end to end over the real network boundary before any service's business logic is fleshed out. Tenant onboarding + RLS built for real here (not stubbed), since every later phase depends on it being right. This replaces a separate "build monolith, then split" step — at 4 services the Compose/networking overhead is small enough to pay once, directly, rather than write the module boundaries twice.

**Phase 2 — Flesh out business logic & saga compensation** With the skeleton proven, build out each service's real logic: the full credit/pool algorithm, RabbitMQ for Invoice's PAID/OVERDUE events consumed by Credit Service, the explicit Order & Inventory saga state machine with compensating actions, and the concurrency test for the shared pool.

**Phase 3 — Operational workflow layer** Near-limit alerts, monthly review report job, manual override, self-service limit-increase request queue — this is the "system suggests, admin decides" layer from the existing plan, now with real services underneath it.

**Phase 4 — Dashboards** Dealer dashboard (tenant-scoped) and Admin dashboard (cross-tenant, audited access) as thin clients over the now-stable APIs.

**Phase 5 — Hardening & deployment** Correlation-id logging across all services; GitHub Actions CI; deploy to Render per the Deployment Architecture section; load/concurrency test the deployed version, not just locally.

**Phase 6 — Optional stretch goals** (pick based on what you want to learn next, not required for v1 to be "done")

- Kubernetes (k3s or minikube) as a learning exercise once services are stable
- Prometheus + Grafana, distributed tracing (Zipkin)
- Eureka/service discovery if you move toward dynamic scaling
- V2 Invoice Discounting Service, extending Invoice Service's existing event contract per the plan's V2 section

**Flag:** the integration-test-first discipline in Phases 1–2 is what prevents the single most common solo-microservices failure mode — months spent on individual services that, when finally wired together, reveal the contracts didn't actually agree. If you take one thing from this plan, make it "prove the whole path works before polishing any one box."

## Detailed Implementation Plan

### Phase 0 — Foundations

**Tasks**

1. Gradle multi-module repo: `settings.gradle.kts` listing `common`, `tenant-catalog-service`, `order-inventory-service`, `credit-service`, `invoice-service`. `common` holds shared DTOs (`LockCreditRequest/Response`, `IssueInvoiceRequest`, invoice event payloads), a `CorrelationIdFilter` (servlet filter writing an incoming or newly-generated `X-Correlation-Id` into MDC and the response headers), and a Feign `RequestInterceptor` that propagates that header on every outbound call.
2. Infra-only `docker-compose.yml` (no app services yet): `postgres` (with an init script under `docker-entrypoint-initdb.d/` creating 4 databases — `tenant_db`, `order_db`, `credit_db`, `invoice_db`), `mongo` (for catalog, used from Phase 2), `rabbitmq` (management plugin enabled, ports 5672 + 15672).
3. **RLS + pooled-connection proof of concept** (the one real technical risk to burn down before any real service is built on top of it): create a throwaway `poc_items(id, tenant_id, value)` table in `tenant_db` with `CREATE POLICY tenant_isolation ON poc_items USING (tenant_id = current_setting('app.current_tenant')::uuid)`. The known failure mode: HikariCP reuses physical connections across requests, so a session variable set once can leak into the next request on the same pooled connection. Recommended fix, built here and reused by every later service: a Spring `@Around` aspect (or a `TransactionSynchronization` registered in a base repository class) that runs `SET LOCAL app.current_tenant = '<id>'` *inside the current transaction*, right after it starts — `SET LOCAL` is automatically reset at commit/rollback, so it can never leak to the connection's next borrower, unlike a plain `SET` on checkout.
4. GitHub Actions skeleton: workflow running `./gradlew build` (compiles only — no tests exist yet).

**Exit criteria**

- `docker compose up` (infra only) brings up Postgres with all 4 databases created, Mongo, and RabbitMQ (management UI reachable at `:15672`).
- The RLS POC test (below) passes reliably, not just once.
- `./gradlew build` succeeds locally and in CI.

**Validation**

- Automated: a JUnit test that opens a small thread pool (e.g. 4 threads), has each thread repeatedly set a *different* tenant id via the `SET LOCAL` aspect inside its own transaction and read back `poc_items`, run for ≥100 interleaved iterations, asserting zero cross-tenant rows are ever visible. Treat any single failure as a blocking bug — this exact mechanism underpins every service's tenant isolation.
- Manual: `docker exec` into the Postgres container and `SELECT * FROM pg_policies WHERE tablename = 'poc_items';` to confirm the policy is actually registered as expected, not just assumed from the migration script.

### Phase 1 — Walking Skeleton Across Real Services

**Scope per service (thin slice — wiring proven, logic stubbed)**

*Tenant & Catalog Service* — `Tenant(id, name, status, creditLimit nullable, riskScore nullable)` using the Phase 0 RLS pattern for real. Endpoints: `POST /tenants` (onboard), `GET /tenants/{id}`, `POST /auth/login` (issues a JWT with `tenant_id` + `role` claims — every other service depends on this from day one). Catalog: `GET /catalog/{tenantId}` returns 2–3 hardcoded products (real Mongo-backed catalog + pricing logic is Phase 2). Security: a Spring Security filter validating the JWT and populating a `TenantContext` (the thing the Phase 0 RLS aspect reads from).

*Order & Inventory Service* — `Order(id, tenantId, items, status)` with status enum `PLACED → CREDIT_LOCKED → STOCK_RESERVED → INVOICED → COMPLETED`, failure states `CREDIT_REJECTED` / `STOCK_REJECTED` / `ROLLED_BACK`. `POST /orders` orchestrates the saga (below). OpenFeign clients: `CreditServiceClient.lockCredit(...)` / `.releaseCredit(lockId)`, `InvoiceServiceClient.issueInvoice(...)`. Stock check is stubbed ("always available") — but a test-only `?simulateStockFailure=true` query param forces the rejection branch, so the compensation path is actually exercised end-to-end without needing real inventory logic yet.

*Credit & Working Capital Service* — `CreditAccount(tenantId, limit, outstanding)`, `PoolBalance` (singleton row). `POST /credit/lock` is a stubbed approval (e.g. approve if `orderValue < ₹1,00,000`) — the real two-level formula is Phase 2. `POST /credit/release/{lockId}`.

*Invoice Service* — `Invoice(id, orderId, tenantId, status, terms)`. `POST /invoices` creates the record as `ISSUED`. No RabbitMQ yet, no further transitions — Phase 2.

**Saga wiring (the actual point of this phase)**

1. `POST /orders` → `CreditServiceClient.lockCredit`. Rejected → order set to `CREDIT_REJECTED`, return 422, saga ends (nothing to compensate yet).
2. Stock check (stubbed true, or forced false via the test flag). False → order set to `STOCK_REJECTED`, then `CreditServiceClient.releaseCredit(lockId)` runs as the compensating action.
3. Both true → `InvoiceServiceClient.issueInvoice(...)`, order set to `COMPLETED`.
4. Correlation id generated at the Order & Inventory entry point, propagated via the `common` module's Feign interceptor and MDC filter (Phase 0) through every hop.

**Docker Compose**: add the 4 service containers, each exposing its own port (e.g. 8081–8084) directly for now — defer the unified API gateway to Phase 4/5, since it adds nothing to proving the skeleton.

**Exit criteria**

- `docker compose up` brings up all 4 services + infra cleanly from a cold start.
- Happy path: `POST /orders` → 201, and an `Invoice` row with status `ISSUED` exists in `invoice_db` referencing that order.
- Forced failure: `POST /orders?simulateStockFailure=true` → order ends `STOCK_REJECTED` *and* the corresponding credit lock's status flips to `RELEASED` in `credit_db` — this is the one case that proves compensation actually works, not just the happy path.
- A JWT issued by `/auth/login` is accepted by all 3 other services (shared signing secret via an env var for now).

**Validation**

- Automated: an integration test (Testcontainers spinning up Postgres + the 4 Spring contexts, or a Testcontainers `ComposeContainer` running the real images) covering the happy path and the forced-failure/compensation path, asserting final state across `order_db`, `credit_db`, `invoice_db`.
- Manual: a committed `.http` file or Postman collection — onboard tenant → login → place order → fetch invoice — run once by hand to confirm the collection itself is correct before relying on it later.
- Correlation-id spot check: `grep` one order's correlation id across `docker compose logs` and confirm it appears in all 3 downstream services' log lines for that request — if it doesn't, the MDC/Feign-interceptor wiring is wrong and every later debugging session will be harder for it.

### Phase 2 — Flesh Out Business Logic & Saga Compensation

**Credit & Working Capital Service — schema**

- `credit_accounts(tenant_id PK, credit_limit numeric, outstanding numeric, throttled boolean, version int)`
- `pool_balance(id=1 singleton, cash numeric, committed_payables numeric, operating_buffer numeric, buffer_percent numeric default 0.15, total_outstanding numeric, version int)` — `total_outstanding` is a denormalized running total updated transactionally alongside every lock/release, rather than a live `SUM(...)` across all tenants on every check, since that would mean locking the entire `credit_locks` table on every order.
- `credit_locks(id, tenant_id, order_id, amount, status[LOCKED|RELEASED|CONSUMED], created_at)`
- `credit_risk_scores(tenant_id, declared_volume, business_vintage, collateral, relationship_category, computed_score, suggested_limit)`
- `limit_increase_requests(id, tenant_id, requested_amount, reason, status[PENDING|APPROVED|REJECTED], reviewed_by, reviewed_at)`
- `credit_overrides(id, tenant_id, old_limit, new_limit, override_reason, set_by, set_at)` — append-only audit log, never updated or deleted
- `processed_operations(operation_key PK, processed_at)` — idempotency guard, checked before any mutating operation

**Credit & Working Capital Service — key logic**

- `RiskScoringService.computeScore(onboardingData)`: implements the plan's point table (declared volume 0–3, vintage 0–2, collateral 0–3, relationship 0–2), `RiskMultiplier` bands 0.5×–2.0×, `SuggestedCreditLimit = declaredVolume × multiplier`, capped by a configurable New-Agent Ceiling (default ₹15L).
- `CreditCheckService.lockCredit(tenantId, orderValue)`, inside one transaction:
  1. `SELECT ... FOR UPDATE` on `pool_balance`, **then** on the `credit_accounts` row for `tenantId` — fix this lock ordering everywhere it's touched, to rule out deadlocks between concurrent orders from different tenants.
  2. `TenantAvailable = creditLimit - outstanding`; `PoolAvailable = allocatablePool - total_outstanding` (allocatablePool computed from `pool_balance` fields per the plan's formula).
  3. `OrderAllowed = orderValue ≤ min(TenantAvailable, PoolAvailable)`.
  4. Allowed → insert `credit_locks` row, increment `outstanding` and `total_outstanding`, commit, return `lockId`. Not allowed → rollback, return rejection (no state change at all).
- `releaseCredit(lockId)`: idempotent via `processed_operations` (`operation_key = "release:" + lockId`) — a second call is a no-op, not an error.
- RabbitMQ consumer on a `invoice.events` topic exchange, routing keys `invoice.paid` / `invoice.overdue`: `invoice.paid` → marks the matching lock `CONSUMED`, decrements `outstanding`/`total_outstanding` (the "rotation"); `invoice.overdue` → sets `throttled=true` on that tenant's `credit_accounts` row, checked by Order & Inventory Service before allowing new orders for that tenant.
- `@Scheduled` near-limit alert job (every 15 min): flags tenants at `utilization = outstanding/limit ≥ 90%` into an `alerts` table for the Admin dashboard.
- `@Scheduled` monthly review job (`0 0 1 * *`): compiles a `monthly_reports` row set.
- `POST /credit/{tenantId}/recompute`: admin-triggered recalculation, enforcing `NewLimit = max(recomputedValue, currentOutstanding)`.

**Order & Inventory Service — schema & logic**

- `orders(id, tenant_id, status, total_value, saga_state, created_at)`, `order_items(order_id, sku, qty, unit_price)`, `inventory(sku PK, qty_available, qty_reserved, version)`.
- Real check-then-commit: `SELECT ... FOR UPDATE` on the relevant `inventory` rows, verify `qty_available - qty_reserved ≥ requested` per line item, increment `qty_reserved` transactionally; compensation decrements it back.
- `OrderSagaOrchestrator`: the state machine from Phase 1, now persisting `saga_state` after every step (not just the final status) so a crash mid-saga is inspectable and recoverable rather than silently stuck — the recovery sweep that reads this is built in Phase 5.
- Idempotency: a client-supplied `Idempotency-Key` header on `POST /orders`, checked the same way as Credit's — a retried request must not double-lock credit or double-reserve stock.

**Invoice Service — logic**

- Explicit allowed-transition map for the state machine (`ISSUED→PAID`, `ISSUED→OVERDUE`, `OVERDUE→PAID`, `*→CLOSED`); an illegal transition returns 409, never silently succeeds.
- `@Scheduled` daily overdue-sweep: finds `ISSUED` invoices past due date, transitions to `OVERDUE`, publishes `invoice.overdue`.
- `POST /invoices/{id}/mark-paid` (admin action, matching the plan's "admin re-enters the Tally reference" flow): transitions to `PAID`, publishes `invoice.paid`.
- Publisher: topic exchange `invoice.exchange`; message payload includes `invoiceId, tenantId, orderId, amount`, plus a message id consumers use for their own idempotency check.

**The one test that matters most — concurrency** Spin up Testcontainers Postgres, seed one tenant + a pool with a known, small `AllocatablePool`. Fire N concurrent `lockCredit` calls (e.g. 20 threads via an `ExecutorService`) whose combined requested value exceeds the pool. Assert the sum of *approved* locks' values never exceeds `AllocatablePool`, and that the count approved is exactly what the `FOR UPDATE` serialization should allow. Run this 50+ times in CI — any flake here is a locking bug, not test flakiness, and is the architecture's actual reason for existing.

**Exit criteria**

- All formulas (RiskMultiplier, pool arithmetic, `NewLimit` safeguard) covered by pure-function unit tests, independent of the database.
- Marking an invoice `PAID` via the API visibly releases credit within the same integration test run (assert Credit DB state after the event is consumed).
- The concurrency test passes consistently across 50+ CI runs.
- An idempotent-retry test (same `Idempotency-Key` sent twice) results in exactly one order, one lock, one invoice — never two.

**Validation**

- Full Phase 1 integration suite still passes, now exercising real logic instead of stubs.
- New suite: credit-math unit tests, the concurrency test, the idempotency test, a RabbitMQ consumption test (publish `invoice.paid`, assert Credit DB state).
- Manual: watch a message appear and get consumed live in the RabbitMQ management UI (`:15672`) during a demo run — this is the single best way to *see* the event-driven part working, not just pass a test on it.

### Phase 3 — Operational Workflow Layer

**Tasks**

- `GET /admin/alerts` on Credit Service, exposing the near-limit alerts table built in Phase 2, for the Admin dashboard to consume.
- `GET /admin/reports/monthly/latest`, exposing the monthly review report.
- `PATCH /credit/{tenantId}/limit` (manual override): body `{newLimit, overrideReason}`, gated by `@PreAuthorize("hasRole('ADMIN')")`, writes an immutable row to `credit_overrides` with `overrideReason`, `setBy`, `setAt` — never updates the account without also writing the audit row in the same transaction.
- `POST /credit/{tenantId}/limit-increase-requests` (tenant role) — writes `requestedAmount` + `reason` into the review queue.
- `GET /admin/limit-increase-requests` (queue, filterable by status) and `POST /admin/limit-increase-requests/{id}/approve|reject` — approval becomes a row in `credit_overrides` exactly like a manual override, keeping one audit trail for both paths.
- Optional but recommended for consistency with the rest of the architecture: publish a `credit.limit_increase_requested` event alongside the DB write, consumed for now by a simple logging consumer — a deliberate extension point for a real notification channel later, costing almost nothing to add now.

**Exit criteria**

- An admin can view alerts and the monthly report, and approve or reject a pending limit-increase request through the API.
- Every override or approval — manual or via a limit-increase request — is visible through a single audit query, with reason, who, and when.
- The `NewLimit = max(recomputedValue, currentOutstanding)` safeguard from Phase 2 also applies here: an override that would cut a limit below current outstanding exposure is rejected, not silently clamped.

**Validation**

- Integration test: submit a limit-increase request → admin approves → tenant's `credit_limit` updates → a subsequent order can actually use the new headroom (not just that the number changed).
- Integration test: attempt an override below current outstanding, assert rejection with a clear error, not a silently adjusted value.
- Manual: pull the audit trail for a tenant after a mix of manual overrides and approved requests, confirm it reads as a coherent history.

### Phase 4 — Dashboards

**Tasks**

- Introduce the API gateway here (Spring Cloud Gateway, or plain Nginx if simpler): routes `/api/tenants/**`, `/api/orders/**`, `/api/credit/**`, `/api/invoices/**` to the respective services. The frontend then talks to one origin, sidestepping CORS configuration across 4 separate ports — this is the actual reason to add a gateway now rather than in Phase 1, when there was no frontend to need it.
- React + Vite frontend (or whichever framework you're already comfortable with — the architecture doesn't care).
- Dealer dashboard: login (stores the JWT in memory, not `localStorage`, to limit XSS token theft — call this out as a deliberate choice), order placement form, order status list (`GET /orders?tenantId=`), invoice list.
- Admin dashboard: cross-tenant utilization table, alerts panel (Phase 3's `/admin/alerts`), limit-increase queue with approve/reject actions, manual override form.

**Exit criteria**

- A dealer can log in, place an order, and watch its status progress through to `INVOICED` (manual refresh is fine for v1 — polling/websockets are not required).
- An admin can log in, see every tenant's utilization, and approve a pending limit-increase request, with the change reflected back in the dealer's view.

**Validation**

- A documented manual E2E walkthrough/demo script, run before calling this phase done — this doubles as your interview demo script later.
- Optional, time permitting: one Playwright/Cypress smoke test for the single most important path (place order → see invoice); otherwise explicitly note this phase as manual-only validation rather than silently skipping it.

### Phase 5 — Hardening & Deployment

**Tasks**

- Confirm correlation-id propagation still works end-to-end now that the gateway sits in front of everything (it's an easy place for a header to get silently stripped).
- GitHub Actions: a build+test job (Testcontainers via Docker-in-Docker, which GH Actions supports natively), then a build+push job publishing Docker images to GitHub Container Registry (`ghcr.io`) on merge to `main`.
- Render: 4 web services (one per image) + 1 Render-managed Postgres instance (the 4 databases from Phase 0) + a Mongo Atlas free-tier instance (or Render's own doc-store equivalent) + CloudAMQP's free tier for RabbitMQ. Environment variables per service: DB URL, JWT signing secret, RabbitMQ URL, gateway's public URL for CORS config.
- The saga-recovery job flagged back in Phase 2: a `@Scheduled` sweep in Order & Inventory Service that finds orders stuck in a non-terminal `saga_state` older than N minutes and either retries the next step or flags them into an admin-visible "needs manual review" list — this is what makes the persisted `saga_state` from Phase 2 actually pay for itself.
- Re-run the Phase 2 concurrency test's logic as a script against the *live* Render URLs (not Testcontainers) — real network latency changes timing in ways a local test won't, and this is the one guarantee (the pool never overdrafts) that absolutely cannot be allowed to regress silently.

**Exit criteria**

- All 4 services are live on Render, reachable only through the gateway's public URL (not directly, to keep the "one origin" property from Phase 4 true in production too).
- A full order-to-invoice flow succeeds against the deployed system, not just locally.
- CI is green on `main` and auto-deploys on merge, with no manual deploy steps.

**Validation**

- A smoke-test script (curl or the Postman collection from Phase 1, pointed at the Render URLs) run immediately after every deploy.
- The concurrency test re-run against the deployed instance; confirm the same guarantee holds under real latency, not just in CI.
- Deliberately kill one service mid-saga (e.g. stop the Credit Service container) and confirm the recovery sweep surfaces the stuck order rather than losing it silently — the one failure-mode test that proves the recovery job actually works, not just that it compiles.

### Phase 6 — Optional Stretch Goals

Not required for v1 to be "done" — pick based on what you want to learn next. Each has its own definition of "done" rather than a shared exit criterion, since any subset (or none) is a valid outcome:

- **Kubernetes (k3s or minikube)**: done when all 4 services + infra run as pods via Helm charts or plain manifests, with the same smoke test from Phase 5 passing against the k3s deployment.
- **Prometheus + Grafana, distributed tracing (Zipkin)**: done when a single Grafana dashboard shows request rate/latency/error rate per service, and a Zipkin trace for one order shows all 4 services' spans joined by the correlation id already threaded through since Phase 1.
- **Eureka/service discovery**: only actually worth doing alongside dynamic scaling (e.g. on Kubernetes with multiple replicas) — done when the gateway routes to services by name through Eureka rather than fixed addresses, and a newly-scaled replica is picked up without a config change.
- **V2 Invoice Discounting Service**: done when it consumes Invoice Service's existing `ISSUED` event (no changes needed to Invoice Service itself, confirming the extension point held) and implements the `LISTED → BID/ACCEPTED → FUNDED → CLOSED` state machine from the plan's V2 section.
