# Technical Specification: Multi-Tenant E-commerce SaaS (Syria)

Version 1.0, 2026-10-14. Stack: Option B (Spring Boot + Thymeleaf + HTMX).

This document is the source of truth for building the MVP. Implement it milestone by milestone (section 17). Each milestone must compile, pass tests and run locally before the next one starts.

---

## 1. Product summary

A SaaS platform that lets small Syrian merchants open an online store in minutes. Each merchant (tenant) gets a storefront on a subdomain, a merchant admin, cash-on-delivery and manual wallet/bank payments, and merchant-run delivery with COD settlement tracking.

**Users**

| Role | Where | Description |
|---|---|---|
| Platform admin | `admin.<domain>` | Operates the SaaS: merchants, plans, billing, suspensions |
| Merchant owner / staff | `<domain>/admin` (login) | Manages one store: catalog, orders, delivery, settings |
| Driver | `<domain>/driver` (login) | Merchant staff with a delivery-only mobile web view |
| Shopper | `<store>.<domain>` | Browses, orders, tracks orders. Login by phone + OTP |

**MVP scope (in)**: tenancy, merchant signup and setup wizard, catalog with variants, SYP/USD pricing, storefront, cart, checkout (COD, wallet transfer, bank transfer), orders with state machine and confirmation step, customer blocklist, delivery zones, driver assignment, COD settlement tracking, notifications (SMS/WhatsApp/email, behind interfaces), SaaS billing with manual payments, Arabic-first i18n with RTL.

**Out of MVP**: mobile app (phase 2, but the REST API skeleton is designed now), card payments, courier API integrations, coupons, custom domains, analytics, iOS.

---

## 2. Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 21 (LTS) | Records, sealed types, pattern matching, virtual threads |
| Framework | Spring Boot 3.5.x | Latest 3.x patch |
| Build | Maven (wrapper committed) | Single Maven module |
| Modularity | Spring Modulith | Module boundaries verified in tests |
| Web UI | Thymeleaf 3 + Thymeleaf Layout Dialect | Server-rendered HTML |
| Interactivity | HTMX 2 + Alpine.js 3 | Served as static files (no CDN dependency at runtime) |
| CSS | Tailwind CSS via standalone CLI | No Node.js; run by `frontend-maven-plugin`-free exec step or script |
| Security | Spring Security 6 | Session auth (web), JWT (API, phase 2) |
| Persistence | Spring Data JPA, Hibernate 6 | `@TenantId` discriminator |
| Database | PostgreSQL 16 | Row-Level Security as second isolation layer |
| Migrations | Flyway | SQL migrations only |
| Background jobs | JobRunr (PostgreSQL storage) | Notifications, cleanup, scheduled tasks |
| Cache | Spring Cache + Caffeine | Redis deferred |
| Object storage | S3-compatible (AWS SDK v2) | MinIO locally, S3 or Cloudflare R2 in prod |
| Validation | Jakarta Bean Validation | |
| Mapping | Plain Java (records + static factories) | No MapStruct in MVP |
| Testing | JUnit 5, AssertJ, Testcontainers, Spring Modulith test, Playwright for Java (smoke E2E) | |
| Observability | Spring Boot Actuator, Micrometer, structured JSON logs, Sentry | |
| Local env | Docker Compose: PostgreSQL, MinIO, Mailpit | |
| CI | GitHub Actions | Build, test, Tailwind build, Docker image |

Base package: `com.unispark.ecommerce`.

---

## 3. Architecture

Modular monolith. One deployable Spring Boot app. Modules are top-level packages under the base package. Modules talk to each other only through their public API (top-level package types) or through application events. Internal packages are `internal`.

```
com.unispark.ecommerce
├── shared/            # money, ids, tenant context, errors, base entities (Modulith "shared" module)
├── tenant/            # stores, tenant resolution, plans/entitlements
├── identity/          # users, roles, login, OTP, sessions
├── catalog/           # categories, products, variants, images
├── inventory/         # stock levels, reservations
├── pricing/           # currencies, exchange rates, price calculation
├── cart/              # carts
├── order/             # orders, state machine, confirmation, blocklist
├── payment/           # payment methods, PaymentProvider SPI, verification
├── delivery/          # zones, fees, drivers, assignments, COD settlement
├── customer/          # shopper profiles, addresses
├── notification/      # NotificationChannel SPI, templates, outbox
├── billing/           # SaaS subscriptions, invoices, manual payments
├── media/             # upload, storage, image URLs
├── storefront/        # shopper-facing web controllers + templates
├── merchantadmin/     # merchant admin web controllers + templates
├── platformadmin/     # platform admin web controllers + templates
└── api/               # REST /api/v1 (phase 2 skeleton)
```

Rules:
- Business logic lives in module services. Controllers are thin: validate input, call a service, return a view or fragment.
- Web modules (`storefront`, `merchantadmin`, `platformadmin`, `api`) depend on domain modules, never the reverse.
- Cross-module side effects (send notification when order is confirmed, release stock when order is cancelled) use Spring application events with `@ApplicationModuleListener`, persisted via Spring Modulith's event publication registry.
- A `ModularityTests` class verifies the module structure (`ApplicationModules.of(App.class).verify()`).

---

## 4. Multi-tenancy

**Model**: shared database, shared schema. Every tenant-owned table has `tenant_id UUID NOT NULL`.

### 4.1 Tenant resolution

| Surface | Resolution |
|---|---|
| Storefront | Host header: `{slug}.<base-domain>` → store by slug. Unknown slug → 404 page |
| Merchant admin / driver | Authenticated user's `tenant_id` (stored in session principal) |
| Platform admin | No tenant (platform scope). Separate security chain |
| API v1 | `X-Store-Id` header (shopper app) or JWT claim (merchant), validated |

A servlet filter `TenantResolutionFilter` sets `TenantContext` (a `ScopedValue` or `ThreadLocal`, cleared in `finally`). Jobs set the context explicitly from the job payload.

### 4.2 Isolation layer 1: Hibernate

- Tenant-owned entities extend `TenantScopedEntity` with `@TenantId private UUID tenantId;`.
- `CurrentTenantIdentifierResolver` reads `TenantContext`.
- No native queries on tenant tables unless they include `tenant_id` and are reviewed.

### 4.3 Isolation layer 2: PostgreSQL RLS

- Two DB roles: `app_owner` (owns tables, runs Flyway) and `app_user` (runtime, `NOBYPASSRLS`, not owner).
- Every tenant table: `ALTER TABLE x ENABLE ROW LEVEL SECURITY; ALTER TABLE x FORCE ROW LEVEL SECURITY;` and a policy:
  ```sql
  CREATE POLICY tenant_isolation ON x
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
  ```
- A `TenantAwareDataSource` wrapper runs `SELECT set_config('app.tenant_id', ?, false)` when a connection is borrowed (if a tenant is set) and `RESET app.tenant_id` when it is returned.
- Platform-admin and cross-tenant jobs use a separate `platform` DataSource with a role that has `BYPASSRLS`, used only by `platformadmin` and `billing` services, explicitly.
- Flyway migration helper: every new tenant table must include the RLS statements. A test fails if any table with a `tenant_id` column lacks an enabled policy.

### 4.4 Isolation tests (required)

`TenantIsolationIT` (Testcontainers): create tenants A and B with data; with context = A, every repository `findAll`/`findById` for B's ids returns nothing; HTTP requests to A's storefront never show B's products; a raw JDBC query as `app_user` with `app.tenant_id` = A returns zero B rows.

---

## 5. Data model (MVP)

All ids are UUID (v7, generated in Java). All tables have `created_at`, `updated_at` (`timestamptz`) and `version` (optimistic locking) unless noted. Translatable text uses `jsonb` like `{"ar": "...", "en": "..."}`.

### Platform tables (no `tenant_id`, no RLS)

| Table | Key columns |
|---|---|
| `plan` | code, name jsonb, monthly_price_usd, limits jsonb (products, staff, orders/month) |
| `store` | id (= tenant id), slug unique, name jsonb, status (TRIAL, ACTIVE, SUSPENDED, CLOSED), plan_code, default_locale, base_currency, display_currency, theme jsonb, contact_phone, whatsapp, created_by |
| `platform_user` | email, password_hash, role (PLATFORM_ADMIN) |
| `subscription` | store_id, plan_code, status, current_period_start/end, grace_until |
| `invoice` | store_id, number, amount, currency, status (OPEN, PAID, VOID), due_date |
| `invoice_payment` | invoice_id, method (WALLET, BANK, CASH), reference, amount, recorded_by, recorded_at |

### Tenant tables (`tenant_id` + RLS)

| Table | Key columns |
|---|---|
| `staff_user` | phone, email, password_hash, name, role (OWNER, MANAGER, STAFF, DRIVER), active |
| `category` | name jsonb, slug, parent_id, sort_order, active |
| `product` | name jsonb, description jsonb, slug, category_id, status (DRAFT, ACTIVE, ARCHIVED), price_currency (SYP/USD) |
| `product_option` | product_id, name jsonb (e.g. Size), values jsonb |
| `product_variant` | product_id, sku, option_values jsonb, price numeric(19,2), compare_at_price, active |
| `product_image` | product_id, variant_id null, storage_key, sort_order, alt jsonb |
| `stock_level` | variant_id unique, on_hand int, reserved int, version |
| `exchange_rate` | from_currency, to_currency, rate numeric(18,6), effective_at, set_by |
| `customer` | phone (unique per tenant), name, blocked bool, block_reason, order_count, refused_count |
| `customer_address` | customer_id, governorate, city, area, landmark, details, lat, lng, phone |
| `cart` | session_key or customer_id, currency, expires_at |
| `cart_item` | cart_id, variant_id, quantity |
| `shop_order` | number (per tenant sequence), customer_id, status, payment_status, payment_method, currency, display_currency, rate_snapshot, subtotal, delivery_fee, total, total_display, address snapshot jsonb, notes, zone_id, driver_id, confirmed_at, delivered_at |
| `order_item` | order_id, variant_id, product snapshot jsonb (name, sku, options), unit_price, quantity, line_total |
| `order_event` | order_id, type, from_status, to_status, actor, payload jsonb, created_at (append-only, no update) |
| `payment_method_config` | method (COD, SYRIATEL_CASH, SHAM_CASH, MTN_CASH, BANK_TRANSFER), enabled, display_name jsonb, account_id, account_name, qr_image_key, instructions jsonb |
| `payment_proof` | order_id, method, transaction_ref, image_key, submitted_at, status (PENDING, APPROVED, REJECTED), reviewed_by, review_note |
| `delivery_zone` | governorate, city, area null, fee, currency, min_order, free_over, eta_text jsonb, active |
| `cod_collection` | order_id, driver_id, amount, currency, collected_at |
| `cod_remittance` | driver_id, amount, currency, remitted_at, received_by, note |
| `notification_log` | channel, recipient, template, status, provider_ref, error |

Indexes: every composite index on tenant tables starts with `tenant_id`. Unique constraints include `tenant_id` (e.g. `(tenant_id, slug)`).

Money: `numeric(19,2)` + explicit currency column, `BigDecimal` in Java wrapped in a `Money` record (amount, currency). Never `double`.

---

## 6. Order and payment rules

### 6.1 Order status

```
PENDING_CONFIRMATION → CONFIRMED → PREPARING → OUT_FOR_DELIVERY → DELIVERED → COMPLETED
PENDING_CONFIRMATION → CANCELLED
CONFIRMED | PREPARING → CANCELLED
OUT_FOR_DELIVERY → REFUSED (customer refused at door) → RETURNED
```

- Implemented as an enum with an explicit allowed-transitions map in `OrderStateMachine`. Any other transition throws `IllegalOrderTransitionException`.
- Every transition writes an `order_event` and publishes `OrderStatusChanged`.
- `REFUSED` increments `customer.refused_count`. Merchant can block a customer from the order page.

### 6.2 Payment status

`UNPAID → AWAITING_VERIFICATION → PAID` or `→ REJECTED` (back to UNPAID, customer can resubmit). COD: `UNPAID → PAID` when the driver marks cash collected.

| Method | At checkout | Becomes PAID when |
|---|---|---|
| COD | Order placed, UNPAID | Driver records `cod_collection` at delivery |
| Wallet (Syriatel Cash, Sham Cash, MTN Cash) | Show account id, name, QR, instructions; shopper submits transaction ref and/or screenshot | Merchant approves proof |
| Bank transfer | Same as wallet | Merchant approves proof |

### 6.3 PaymentProvider SPI

```java
public sealed interface PaymentProvider permits ManualPaymentProvider, CodPaymentProvider /*, CardPaymentProvider later */ {
    PaymentMethod method();
    CheckoutInstructions instructionsFor(Order order, PaymentMethodConfig config);
    PaymentResult onCheckout(Order order);          // initial payment status
}
```

New methods are added as new implementations; the order module never switches on payment method directly.

### 6.4 Checkout rules

- Blocked customers cannot place orders (generic error message, no reason disclosed).
- New customers (0 completed orders) are limited to a configurable max order total and max 2 open orders.
- Phone OTP verification at checkout (feature-flagged per store; OTP sender behind `NotificationChannel`).
- Stock is reserved at order placement (`reserved += qty` with optimistic lock); released on CANCELLED/RETURNED; deducted from `on_hand` on DELIVERED.
- Order totals are recalculated server-side; client prices are never trusted.
- Order creation is idempotent: checkout form carries an idempotency token; a duplicate submit returns the existing order.

---

## 7. Pricing and currency

- Supported: `SYP` (new Syrian pound, post-2026 redenomination) and `USD`.
- Each product has a `price_currency`. Store has `display_currency`. Merchant sets the USD→SYP rate in admin (history kept in `exchange_rate`).
- Display price = convert(variant price) with store rounding rule (`NONE`, `NEAREST_5`, `NEAREST_10`, `NEAREST_100` for SYP).
- Order stores both the charge currency totals and the display totals, plus `rate_snapshot`. Historical orders never recompute.
- Formatting via `MessageFormat`/`NumberFormat` per locale; option for Western or Arabic-Indic digits (store setting).

---

## 8. Delivery

- `delivery_zone` defines fee and rules by governorate → city → (optional) area. Most specific active match wins.
- Checkout requires choosing a zone-backed address; no matching zone → "delivery not available" message.
- Merchant assigns orders to a driver (`staff_user` with role DRIVER).
- Driver view (`/driver`, mobile-first): list of assigned orders, call/WhatsApp customer buttons, map link, actions: out for delivery, delivered + cash collected (amount prefilled), refused.
- COD settlement page: per driver, collected vs remitted vs outstanding; merchant records remittances.
- `ShippingProvider` interface exists with one implementation `MerchantDeliveryProvider`; a `ManualCourierProvider` (courier name + tracking ref) is phase 2.

---

## 9. Web layer (Thymeleaf + HTMX)

### 9.1 Conventions

- Layouts: `templates/layouts/{storefront,admin,driver,platform}.html` using Layout Dialect.
- Fragments: `templates/<area>/fragments/*.html`. HTMX requests (header `HX-Request`) return fragments; full requests return full pages. Helper: `HtmxView` utility or `@HxRequest`-style check in controllers.
- Forms: standard POST + PRG for full pages; `hx-post` with fragment swap for inline edits. CSRF token included in HTMX requests via a global `hx-headers` on `<body>`.
- Validation errors re-render the form fragment with field messages.
- No inline `<script>` except Alpine `x-data`. All JS from `/static/js` (htmx.min.js, alpine.min.js, app.js).
- `<html lang="${locale}" dir="${rtl ? 'rtl' : 'ltr'}">`. Tailwind logical utilities only (`ms-`, `me-`, `ps-`, `pe-`, `start-`, `end-`), never `ml-`/`mr-`/`left-`/`right-`.

### 9.2 Storefront routes (`{slug}.<domain>`)

| Route | Purpose |
|---|---|
| `GET /` | Home: banner, featured categories/products (theme sections) |
| `GET /c/{slug}` | Category listing, pagination, sort |
| `GET /p/{slug}` | Product page, variant selection (Alpine), add to cart (HTMX) |
| `GET /search?q=` | PostgreSQL full-text + trigram search |
| `GET /cart`, `POST /cart/items`, `PATCH /cart/items/{id}`, `DELETE /cart/items/{id}` | Cart (HTMX fragments, cart badge updates via `HX-Trigger`) |
| `GET /checkout`, `POST /checkout` | Address, zone, payment method, OTP, place order |
| `GET /orders/{number}?t={token}` | Order tracking + payment instructions + proof upload |
| `GET /account/orders` | Shopper order history (after OTP login) |
| `GET /lang/{ar|en}` | Switch locale (cookie) |

### 9.3 Merchant admin routes (`/admin`)

Dashboard (today's orders, pending verifications, outstanding COD), Orders (list with filters, detail, transitions, assign driver, verify proof, block customer), Products (list, create/edit with variants, images upload), Categories, Inventory, Customers, Delivery zones, Drivers/staff, COD settlement, Payment methods, Store settings (name, logo, theme colors, currency, rate, rounding, locale), Billing (current plan, invoices, payment instructions).

Setup wizard after signup: store name + slug → logo/colors → payment methods → first delivery zone → first product → go live.

### 9.4 Platform admin (`admin.<domain>`)

Stores (list, detail, suspend/activate), Plans, Subscriptions, Invoices (create, record manual payment, void), sanctions/KYC screening checkbox + notes at store activation.

### 9.5 Performance budget (storefront)

- HTML < 50 KB gzipped per page; total JS < 40 KB gzipped; CSS < 30 KB gzipped.
- Images via `media` module: WebP, responsive `srcset` (320/640/1024 widths), lazy-loaded.
- `Cache-Control` for static assets with content hashing; product/category pages cacheable for anonymous users at CDN (short TTL) — cart/checkout `no-store`.
- Service worker (phase 1.5): cache static assets, offline fallback page.

---

## 10. Security

- Separate `SecurityFilterChain`s: storefront (shopper session, optional login), merchant admin (`/admin/**`, form login, roles), driver (`/driver/**`, role DRIVER), platform (`admin.<domain>`), API (`/api/**`, stateless JWT, phase 2).
- Passwords: BCrypt. Merchant login by email or phone. Login rate limiting (Bucket4j, per IP + per account).
- Session cookies: `Secure`, `HttpOnly`, `SameSite=Lax`. CSRF on for all web chains.
- Authorization: method security (`@PreAuthorize`) on admin services by role; staff can only act within their tenant (enforced by tenancy layers).
- Uploads: allowlist content types (jpeg, png, webp), max 5 MB, re-encode images server-side, random storage keys, no user-controlled paths.
- Merchants never edit templates. Theme = settings only (colors, logo, font choice, section toggles). All user text escaped (`th:text`, never `th:utext` for user content).
- Secrets via environment variables; nothing in the repo. Audit log for admin actions (`order_event` + `audit_log` for settings changes).
- Payment proof images and customer data are private: served through signed, short-lived URLs.

---

## 11. Internationalization

- Locales: `ar` (default) and `en`. `messages_ar.properties`, `messages_en.properties` (UTF-8). Locale from cookie, then store default.
- Translatable entity fields are `jsonb` `{ar, en}`; `LocalizedText` value type with fallback to the other language.
- Dates in `Asia/Damascus` timezone; stored as `timestamptz` UTC.
- Every template string goes through `#{...}`; a test fails if a key exists in one bundle and not the other.

---

## 12. Notifications

- `NotificationChannel` SPI: `SmsChannel`, `WhatsAppChannel`, `EmailChannel`. MVP ships `LoggingChannel` (dev), `EmailChannel` (SMTP), and adapter stubs for SMS/WhatsApp providers configured by properties.
- Templates per event and locale (`order_placed`, `order_confirmed`, `out_for_delivery`, `delivered`, `payment_proof_rejected`, `otp`).
- Sent asynchronously by JobRunr from events (`OrderStatusChanged`), with retries and `notification_log`.
- Merchant receives new-order alerts (email + optional WhatsApp).

---

## 13. REST API (phase 2 skeleton)

- Prefix `/api/v1`, JSON, stateless JWT. Reuses domain services; no business logic in API controllers.
- MVP deliverable: package structure, security chain, `GET /api/v1/stores/{slug}`, `GET /api/v1/products`, `GET /api/v1/products/{id}` with OpenAPI docs (springdoc). The rest is built with the mobile app.

---

## 14. Background jobs

| Job | Schedule |
|---|---|
| Send notification | On event |
| Expire abandoned carts | Hourly |
| Release stale reservations (orders cancelled by timeout) | Every 15 min |
| Auto-cancel unconfirmed orders older than N hours (store setting) | Every 15 min |
| Subscription grace check → suspend store | Daily |
| Generate monthly invoices | Daily (creates on period boundary) |

All jobs set `TenantContext` from their payload or run in platform scope explicitly.

---

## 15. Configuration and environments

Profiles: `local`, `test`, `prod`.

| Variable | Purpose |
|---|---|
| `APP_BASE_DOMAIN` | e.g. `example.com` (local: `lvh.me:8080` so subdomains resolve to localhost) |
| `DB_URL`, `DB_APP_USER`, `DB_APP_PASSWORD`, `DB_OWNER_USER`, `DB_OWNER_PASSWORD`, `DB_PLATFORM_USER`, `DB_PLATFORM_PASSWORD` | Database roles |
| `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_PUBLIC_BASE_URL` | Media |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD` | Email |
| `SMS_PROVIDER`, `SMS_API_KEY`, `WHATSAPP_TOKEN`, `WHATSAPP_PHONE_ID` | Messaging (optional) |
| `SENTRY_DSN` | Errors |
| `JWT_SECRET` | API (phase 2) |

`docker-compose.yml` (local): `postgres:16` (init script creates the three roles), `minio`, `mailpit`. App runs from the IDE or `./mvnw spring-boot:run`.

Seed data (`local` profile): platform admin, two demo stores (`demo1`, `demo2`) with products, zones and payment methods.

---

## 16. Testing and quality

- Unit tests for domain logic (state machine, pricing, zone matching, money rounding).
- Integration tests with Testcontainers PostgreSQL (real RLS, real Flyway migrations). No H2.
- `ModularityTests` (Spring Modulith verify + generated docs).
- `TenantIsolationIT` (section 4.4) and `RlsCoverageIT` (every tenant table has RLS).
- Web tests with MockMvc for controllers (full page and HTMX fragment variants).
- Playwright smoke test: shopper places a COD order; merchant confirms; driver delivers.
- i18n bundle parity test.
- Coverage target: 80% for domain modules.
- CI (GitHub Actions): `./mvnw verify` (includes Tailwind build), build Docker image on main.

---

## 17. Implementation milestones

Each milestone ends with: all tests green, app runs locally, short notes in `docs/progress.md`.

| # | Milestone | Done when |
|---|---|---|
| M0 | Project skeleton: Maven, Spring Boot, Modulith, Docker Compose, Flyway, three DB roles, Tailwind CLI build, base layouts (ar/en, RTL), health endpoint, CI | `./mvnw verify` green; `localhost:8080` shows a localized placeholder page in RTL |
| M1 | Tenancy core: `store`, `TenantContext`, resolution filter (subdomain), Hibernate `@TenantId`, `TenantAwareDataSource`, RLS on a sample table | `TenantIsolationIT` and `RlsCoverageIT` pass |
| M2 | Identity: platform admin login, merchant signup (creates store + owner), staff roles, merchant login, security chains | Merchant signs up and reaches empty dashboard; cross-chain access denied in tests |
| M3 | Catalog + media: categories, products, options/variants, image upload to MinIO with WebP resizing, admin CRUD with HTMX | Merchant creates a product with 2 variants and images |
| M4 | Pricing: currencies, exchange rate admin, rounding, `Money` | Unit tests for conversion and rounding pass |
| M5 | Storefront browse: home, category, product, search, locale switch, theme settings | Shopper browses demo store in ar and en; Lighthouse mobile performance ≥ 90 |
| M6 | Cart + inventory reservations | Cart works via HTMX; stock cannot go negative under concurrent test |
| M7 | Delivery zones + customer addresses | Zone matching tests pass; checkout fee computed |
| M8 | Checkout + orders: COD, wallet, bank; OTP (logging channel); idempotency; new-customer limits; blocklist; order state machine; order events | Playwright: COD order placed and visible in admin |
| M9 | Merchant order management: list/filter, confirm, cancel, verify payment proof, assign driver | Merchant processes an order end to end |
| M10 | Driver view + COD settlement | Driver delivers and collects; settlement page shows outstanding balance |
| M11 | Notifications: SPI, templates, JobRunr dispatch, email via Mailpit | Order status changes produce notifications in Mailpit and `notification_log` |
| M12 | Billing: plans, subscriptions, invoices, manual payment recording, grace/suspension job, platform admin screens | Suspended store shows "temporarily unavailable" storefront |
| M13 | Setup wizard + polish: onboarding flow, empty states, error pages, SEO meta, sitemap per store | New merchant goes live in under 15 minutes in a manual run-through |
| M14 | API v1 skeleton + OpenAPI | Endpoints in section 13 return data; docs at `/api/docs` |
| M15 | Production readiness: Dockerfile, prod profile, Sentry, structured logs, backup notes, rate limits | Image builds and runs against a fresh database |

---

## 18. Non-functional requirements

- Storefront p95 server response < 300 ms for cached catalog pages, < 800 ms for checkout.
- Works on Android Chrome on a low-end device over a slow 3G-class connection (test with throttling).
- Graceful errors: friendly Arabic/English error pages, no stack traces to users.
- Backups: managed PostgreSQL with point-in-time recovery; documented restore procedure.
- All timestamps UTC in DB; display in `Asia/Damascus`.

---

## 19. Open questions

- Product name and base domain.
- SMS and WhatsApp providers that serve Syrian numbers reliably.
- Hosting provider and region (Europe, Turkey or Gulf), and vendor acceptance of the business.
- Legal review: Syrian e-commerce regulations, merchant screening process, terms of service.
- Whether shoppers need accounts in MVP or order-tracking links are enough (spec assumes optional OTP login).
