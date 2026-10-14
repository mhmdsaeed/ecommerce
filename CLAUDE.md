# CLAUDE.md

Multi-tenant e-commerce SaaS for small merchants in Syria. Spring Boot modular monolith with server-rendered Thymeleaf + HTMX. The full specification is in `SPEC.md`; read the relevant section before working on a feature.

## Commands

- Start local services: `docker compose up -d` (PostgreSQL, MinIO, Mailpit)
- Run app: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
- Build + all tests: `./mvnw verify`
- Single test: `./mvnw test -Dtest=ClassName`
- Tailwind (watch): `./scripts/tailwind.sh --watch`
- Local stores: `http://demo1.lvh.me:8080`, merchant admin: `http://lvh.me:8080/admin`, Mailpit: `http://localhost:8025`

## Working rules

- Work milestone by milestone (`SPEC.md` section 17). Finish one, run `./mvnw verify`, update `docs/progress.md`, then stop and summarize before starting the next.
- Write or update tests with every change. Integration tests use Testcontainers PostgreSQL, never H2.
- Never weaken tenant isolation. Every tenant-owned table needs `tenant_id`, a Hibernate `@TenantId` entity field, and an RLS policy in its Flyway migration. `TenantIsolationIT` and `RlsCoverageIT` must stay green.
- Schema changes only through new Flyway migrations (`V<n>__description.sql`). Never edit an applied migration.
- Respect module boundaries (Spring Modulith). Other modules use only a module's top-level package; internals go in `internal`. Cross-module side effects use application events.
- Controllers stay thin. Business logic belongs in module services.
- Money is `Money` (BigDecimal + currency). Never use `double` or `float` for amounts.
- Order status changes only through `OrderStateMachine`, and every change writes an `order_event`.
- All user-facing text goes through message bundles (`messages_ar.properties` and `messages_en.properties`, keep both in sync). Arabic is the default locale.
- RTL-safe CSS: only Tailwind logical utilities (`ms-`, `me-`, `ps-`, `pe-`, `start-`, `end-`), never `ml-`, `mr-`, `left-`, `right-`.
- Escape user content with `th:text`. Never use `th:utext` for user data. Merchants never edit templates.
- HTMX requests return fragments; full requests return pages. Include the CSRF token in HTMX requests.
- Keep storefront pages light (see the performance budget in `SPEC.md` 9.5). Don't add JS libraries without a clear need.
- No secrets in the repo; configuration comes from environment variables (`SPEC.md` section 15).

## Code style

- Java 21: records for DTOs and value objects, sealed interfaces for SPIs, `var` for obvious local types.
- Constructor injection only. No field injection.
- Package by module, then by layer inside `internal` (e.g. `order.internal.web`, `order.internal.persistence`).
- Test names describe behavior: `confirmsPendingOrder()`, `rejectsTransitionFromDeliveredToCancelled()`.
