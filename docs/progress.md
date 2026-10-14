# Progress

Milestones from `SPEC.md` section 17. Each entry: what was built, how it was verified, and what is deferred.

## M0: Project skeleton (done, 2026-10-14)

**Done when:** `./mvnw verify` green; `localhost:8080` shows a localized placeholder page in RTL. Both confirmed.

### What was built

- **Build:** single Maven module, wrapper committed (Maven 3.9.11), Java 21, Spring Boot 3.5.16, Spring Modulith 1.4.13. Base package `com.unispark.ecommerce`.
- **Modules:** `shared` (declared as Modulith shared module) and `storefront`. The other modules from SPEC 3 are created when their milestone starts. `ModularityTests` verifies boundaries and writes PlantUML docs and module canvases to `target/spring-modulith-docs`.
- **Database roles** (`docker/postgres/init/01-roles.sh`, used by both docker-compose and Testcontainers):
  - `app_owner`: owns database and `public` schema, runs Flyway, NOBYPASSRLS.
  - `app_user`: runtime DataSource, NOBYPASSRLS, owns nothing, cannot create or alter tables.
  - `app_platform`: BYPASSRLS, owns nothing. Not wired yet (M1 adds the platform DataSource).
  - Default privileges give both runtime roles SELECT/INSERT/UPDATE/DELETE on every table `app_owner` creates.
- **Flyway:** `V1__event_publication.sql` (Spring Modulith event registry). Hibernate runs with `ddl-auto: validate`.
- **docker-compose:** `postgres:16`, MinIO, Mailpit (`axllent/mailpit:v1.31`).
- **Tailwind:** v4.3.3 standalone CLI, downloaded by `scripts/tailwind.sh` (macOS/Linux) or `scripts\tailwind.cmd` (Windows) into `.tools/` with a pinned SHA-256 per platform. Runs in `generate-resources`, so `./mvnw verify` always builds CSS. `-Dtailwind.skip` skips it. Placeholder page CSS: 10.2 KB, 3.0 KB gzipped.
- **Web:** `layouts/base.html` sets `lang`/`dir` from the locale, loads vendored HTMX 2.0.11 and Alpine 3.17.4 (36.8 KB gzipped together, inside the 40 KB budget) and has the CSRF `hx-headers` hook ready for M2. Area layouts `storefront`, `admin`, `driver`, `platform` decorate it.
- **i18n:** Arabic default, English second. Locale stored in a `lang` cookie, switched with `?lang=ar|en`; unsupported languages are ignored. `MessageSource` is declared explicitly because there is no base `messages.properties`.
- **Ops:** Actuator exposes only `health` (with DB check, liveness/readiness probes) and `info`. Virtual threads on. Stack traces never rendered.
- **CI:** `.github/workflows/ci.yml` runs `./mvnw -B verify` on pushes to main and on PRs, caches Maven and the Tailwind binary, uploads the Modulith docs.

### Tests (15, all green)

| Test | Checks |
|---|---|
| `ModularityTests` | Module boundaries; generates module docs |
| `MessageBundleParityTest` | ar and en bundles have the same keys, none blank |
| `RtlSafeTemplatesTest` | No `ml-`/`mr-`/`pl-`/`pr-`/`left-`/`right-`/`text-left`... in templates (plus self-tests of the detector) |
| `DatabaseRolesIT` | Runtime is `app_user`; RLS bypass flags of all three roles; Flyway installed by `app_owner`; `app_owner` owns every table; runtime roles have DML on migrated tables; `app_user` cannot create or alter tables |
| `ApplicationSmokeIT` | Health UP with DB; Flyway applied; `/css/app.css` served; `/actuator/env` and `/actuator/prometheus` not exposed |
| `PlaceholderPageIT` | Arabic RTL by default; `?lang=en` switches to LTR and sets the cookie; cookie is honored; unsupported `?lang=fr` ignored; no unresolved `??key??` messages |

Also run manually: `docker compose up -d postgres` + `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`, then `/` (Arabic RTL page) and `/actuator/health` (UP) at 390 px and 1280 px widths, no horizontal overflow.

### Decisions and deviations

- **MinIO image:** MinIO stopped publishing community images on Docker Hub (`minio/minio` returns "repository does not exist"). Compose uses `chainguard/minio`, pinned by digest. There is no bucket-init container; the media module creates the bucket in M3. MinIO was not started during M0 verification (Docker Hub rate limit in the build environment), so check it on the first `docker compose up -d`.
- **Spring Security is not on the classpath yet** (M2). Adding it now would put every page behind the default login.
- **Deferred dependencies:** JobRunr (M11), AWS SDK v2 (M3), Caffeine (M5), Bucket4j (M2), springdoc (M14), Playwright (M8), Sentry and JSON logs (M15). `micrometer-registry-prometheus` is present but its endpoint is not exposed until it can be secured.
- **Placeholder route:** `GET /` lives in `storefront.internal.web.PlaceholderController` and is replaced by the tenant-resolved home page (M1 resolution, M5 page).
- **Product name** is still an open question (SPEC 19); the bundles use the neutral "منصة المتاجر" / "Store Platform".

## M1: Tenancy core (done, 2026-10-14)

**Done when:** `TenantIsolationIT` and `RlsCoverageIT` pass. Both green, inside a green `./mvnw verify` (96 tests).

### What was built

- **`shared` (public API):**
  - `TenantContext`: ThreadLocal, `enter()` scopes restore the previous tenant, plus `runAs`/`callAs` for jobs.
  - `BaseEntity`: UUIDv7 id assigned in Java (`Ids`), `created_at`, `updated_at`, `@Version`.
  - `TenantScopedEntity`: `@TenantId tenant_id`; `@PrePersist` refuses to save outside a tenant context.
  - `LocalizedText`: ar/en jsonb with fallback. `Currency`: SYP, USD.
  - `PlatformDatabase`: a `JdbcClient` on `app_platform` (BYPASSRLS).
- **`shared.internal.persistence`:**
  - `TenantAwareDataSource`: wraps the Hikari pool. On borrow it sets `app.tenant_id` to the tenant, or `''` when there is none; on close it clears it. Setting on every borrow means a failed reset can never leak a tenant.
  - `TenantIdentifierResolver`: with no tenant it returns the nil UUID, deliberately not a root tenant, so tenant queries return nothing in both layers.
  - `PersistenceConfig`: builds both pools. The platform pool is not a `DataSource` bean, so it can't be injected by accident.
- **`tenant` module (new):**
  - `store` entity, plus the `StoreLookup` / `StoreSummary` / `StoreStatus` API.
  - `StoreHosts` classifies a host as platform, store, or invalid store host. `admin` and `www` are reserved; ports and case are ignored.
  - `TenantResolutionFilter` runs before where Spring Security will sit and skips static paths and actuator. Unknown, closed or malformed store hosts get a 404.
  - `LocalDemoStores` seeds `demo1` and `demo2` in the `local` profile.
- **Migrations:**
  - `V2__store.sql`: slug is one DNS label, 3 to 32 characters; CHECKs on status, locale and currencies.
  - `V3__tenant_rls_helper.sql`: `enable_tenant_rls(regclass)` enables and forces RLS and creates the `tenant_isolation` policy. **Every future tenant table calls `SELECT enable_tenant_rls('<table>');` in its migration.**
  - The policy uses `NULLIF(current_setting('app.tenant_id', true), '')::uuid`. After a reset, `current_setting` returns `''`, and a plain `::uuid` cast would raise an error.
- **Sample RLS table:** `isolation_probe` is test-only (`src/test/resources/db/testmigration`, enabled in `application-test.yml`), so the production schema has no throwaway table.
- **Web:** the placeholder page greets shoppers with the store name on a store subdomain. A localized `error/404.html` is added.

### Tests (96, all green: 44 unit, 52 integration)

| Test | Checks |
|---|---|
| `TenantIsolationIT` | SPEC 4.4 with tenants A and B: JPA `findAll`/`findById`/derived/JPQL show only A; nothing is visible without a tenant; saving without a tenant is refused; raw SQL as A sees and changes no B rows, and inserting or moving rows into B violates RLS; a plain `app_user` connection with `app.tenant_id = A` sees zero B rows; the platform role sees both tenants; HTTP on A's host shows only A's data; the platform host shows none |
| `RlsCoverageIT` | Every table with `tenant_id`: RLS enabled and forced, exactly one `tenant_isolation` policy (permissive, ALL commands, all roles, USING + WITH CHECK), `tenant_id` NOT NULL, unique and composite indexes lead with `tenant_id`. Every table without `tenant_id` is on the platform allowlist (`flyway_schema_history`, `event_publication`, `store`) |
| `TenantAwareDataSourceIT` | On one physical connection: binds to the tenant, binds to `''` with no tenant, clears on return, overwrites a stale tenant, survives a rollback of the first transaction, double close is safe |
| `TenantResolutionIT` | Store name on its subdomain (ar/en); port and case ignored; TRIAL and SUSPENDED stores served; unknown, closed, multi-label and too-short hosts get 404; base, `admin.`, `www.` and `localhost` hosts are platform; static assets skip lookup; tenant cleared after the request |
| `NotFoundPageIT` | Real server, raw `Host` header: the unknown-store 404 page in Arabic and in the cookie language; unknown paths get the HTML page, not JSON |
| `TenancyArchitectureTests` | Only `platformadmin`, `billing` and `shared` may use `PlatformDatabase`; `@TenantId` appears only in `TenantScopedEntity` |
| Unit | `TenantContextTest`, `IdsTest`, `LocalizedTextTest`, `StoreHostsTest` |

**Checking the tests catch real breakage:** with `enable_tenant_rls` removed from the probe table, `RlsCoverageIT` (2 tests) and the 4 raw-SQL isolation tests fail. The JPA and HTTP isolation tests still pass, which shows the Hibernate layer isolates tenants on its own.

**Manual check:** with docker-compose Postgres and the `local` profile, V2 and V3 applied and `demo1`/`demo2` were seeded. With the `Host` header set: `demo1.lvh.me:8080` shows "أهلاً بك في متجر الياسمين" ("Welcome to Jasmine Store" with `?lang=en`), `demo2` shows its own name, `nosuch.lvh.me:8080` returns the 404 page, `lvh.me:8080` shows the platform placeholder, and health is UP.

### Decisions and deviations

- **Spring problem details turned off** (`spring.mvc.problemdetails.enabled: false`). With it on, every unknown URL answered browsers with JSON. RFC 7807 errors return with the REST API in M14, scoped to `/api/**`.
- **`spring.flyway.url` is set explicitly** (`${DB_URL:}`). Flyway cannot derive the URL from the wrapped DataSource. The empty default only covers auto-configuration condition evaluation in tests.
- **The filter takes `@Lazy StoreLookup`.** Tomcat creates filters before the rest of the context; eager JPA there broke `RANDOM_PORT` tests and slows startup.
- **Store status:** CLOSED stores answer 404. SUSPENDED stores are still served until billing (M12) adds the "temporarily unavailable" page.
- **Unmapped `store` columns:** `plan_code`, `theme` and `created_by` exist in the schema but are mapped in M12, M5 and M2.
- **Hibernate 6.6 has no UUIDv7 generator**, so `Ids` implements RFC 9562 v7 with `SecureRandom`.

### Notes for later milestones

- **Switch tenants only outside a transaction.** The connection is bound when it is borrowed. A mismatch fails closed (both layers return nothing), but it is still a bug.
- **Async event listeners (M8+) don't inherit `TenantContext`.** Events must carry `tenantId`, and listeners wrap their work in `TenantContext.runAs`.
- **M2:** merchant admin and driver set the tenant from the session principal. Signup must reject `StoreHosts.RESERVED_SUBDOMAINS` as slugs. *(Done in M2; the reserved list moved to `StoreSlugs.RESERVED`.)*
- **New platform tables** must be added to `RlsCoverageIT.PLATFORM_TABLES`.

## M2: Identity (done, 2026-10-14)

**Done when:** a merchant signs up and reaches the empty dashboard, and tests deny cross-chain access. Both confirmed (`MerchantSignupIT`, `SecurityChainsIT`), inside a green `./mvnw verify` (195 tests).

### What was built

- **Sites and areas.** The tenant module now exposes `Sites` and `Site`:
  - `MAIN` is `<base>`, `www.<base>` and foreign hosts (signup, `/admin`, `/driver`).
  - `PLATFORM_ADMIN` is `admin.<base>`. Platform pages live under `/platform` because `/` is the storefront route; `admin.<base>/` redirects to `/platform`.
  - `STOREFRONT` is `<slug>.<base>`.
  - `StoreSlugs` holds the slug rules and a reserved list (admin, www, api, platform, ...), shared by signup and host parsing.
- **Security chains** (`identity.internal.security.SecurityConfig`), each with its own `AuthenticationManager`:
  1. Platform admin: every request on `admin.<base>`. Role PLATFORM_ADMIN, form login at `/platform/login`, everything outside `/platform` denied.
  2. Merchant admin: `/admin/**` on MAIN. Roles OWNER, MANAGER, STAFF.
  3. Driver: `/driver/**` on MAIN. Role DRIVER.
  4. Site: everything else, open. `/admin`, `/driver` and `/platform` reaching this chain (wrong host) and `/signup` on storefronts answer 404. CSRF failures still get 403.
  - Sessions are host-only cookies, and roles are checked per chain, so a session replayed on another area or host gets 403.
  - CSRF is on everywhere. Session cookies are `Secure` unless `SESSION_COOKIE_SECURE=false` (the local profile). Boot's in-memory default user is excluded.
- **Staff login.** The form takes the store address, email or phone, and password. `StaffAuthenticationProvider` resolves the store first and looks the user up *inside that store's tenant*, so authentication never bypasses RLS.
  - Unknown stores and users still cost one BCrypt check, so response timing doesn't reveal which accounts exist.
  - Phones are accepted in any Syrian spelling, including Arabic-Indic digits (`PhoneNumbers`, stored as E.164). Emails are lowercased.
  - On success, drivers go to `/driver` and everyone else to `/admin`, whichever login page they used, or back to the page that asked for login. A `store` cookie prefills the form next time.
- **`StaffSessionFilter`** runs `/admin` and `/driver` requests in the principal's tenant (SPEC 4.1). It re-checks the account on every request, so disabling a staff member, changing their role or closing the store ends the session immediately.
- **Rate limits** (`LoginThrottle`, Bucket4j buckets in a bounded Caffeine cache, in memory):
  - 5 attempts per account per 15 minutes, reset on success.
  - 30 login attempts per client address per 5 minutes.
  - 5 signups per address per hour.
  - Platform login uses the same limits.
- **Signup** (`MerchantSignupService`): validates slug, phone, optional email and password (8+ characters, at most 72 bytes because of BCrypt). It then generates the store id, enters that tenant, and creates the TRIAL store and its OWNER in one transaction. It records `store.created_by`, logs the owner in (new session id), and redirects to `/admin`. The store name is stored in the signup locale; the other language falls back to it.
- **Staff management** at `/admin/staff`: list, add (with a temporary password), enable and disable. Owners manage everyone except owners; managers manage staff and drivers; nobody can add an owner or change their own account. Enforced by `@PreAuthorize` on `StaffManagementService` and `StaffRole.canManage`.
- **Platform admin:** `/platform/login` and a dashboard listing the newest 50 stores across tenants (via `PlatformDatabase`; dates in Asia/Damascus). The first admin comes from `PLATFORM_ADMIN_EMAIL` and `PLATFORM_ADMIN_PASSWORD` while no platform user exists (`PlatformAdminBootstrap`).
- **Migration `V4__identity.sql`:**
  - `platform_user` is a platform table.
  - `staff_user` is a tenant table with `enable_tenant_rls`, unique `(tenant_id, email)` and `(tenant_id, phone)`, and CHECKs for lowercase email, E.164 phone, role, and at least one of email or phone.
- **UI:** signup, staff login (shared by admin and driver), dashboard (empty state with the storefront link), staff page, driver home, platform login and dashboard, and `error/403.html`. A shared `fragments/forms.html` provides inputs with errors, submit buttons and alerts. The main-site placeholder links to signup and login. 106 new message keys in each bundle (118 total), still in parity.
- **Local seed:** platform admin `admin@lvh.me`; demo1 owner, staff and driver; demo2 owner. All are listed in the README.

### Tests (195, all green: 97 unit, 98 integration)

| Test | Checks |
|---|---|
| `MerchantSignupIT` (12) | Arabic form with CSRF; signup → `/admin` → dashboard shows owner, store name, storefront link and TRIAL; store and owner rows (TRIAL, `created_by`, normalized phone and email, `{bcrypt}` hash); English signup stores the `en` name; the new owner logs in by phone; taken, reserved and malformed slugs; bad phone, email and password reported together; required fields; typed values kept but password not echoed; rejected signup creates nothing; CSRF required; 6th signup per address limited |
| `SecurityChainsIT` (24) | Anonymous visitors go to each area's login; login pages render; owner logs in by email and phone (any spelling); role routing from either login page; return to the saved page; wrong password, unknown store and unknown user give the same error; staff can't log in through another store; closed store refused; disabled account refused (only after the password matches); CSRF on login; session id changes on login; account lockout after 5 failures (other accounts unaffected); per-address limit; owner session refused in driver and platform areas; driver refused in admin; STAFF can't reach staff management; platform session refused in merchant and driver areas; credentials only work on their own login page; storefront hosts serve no admin, driver, platform or signup pages; main site serves no platform pages; disabling a staff member or closing the store ends live sessions; logout |
| `StaffManagementIT` (9) | Lists only own store's staff; owner adds a driver who can log in; managers add staff but not managers or owners; nobody adds an owner; duplicate and missing contact details; same phone allowed in two stores; disable ends session, enable restores login; can't disable self or superiors; another store's staff id → 404, untouched |
| Unit | `PhoneNumbersTest` (24), `StoreSlugsTest`, `StaffRoleTest`, `CredentialsTest`, `StoreHostsTest` (sites, store host building) |
| Updated | `RlsCoverageIT`: `staff_user` covered, `platform_user` allowlisted; the index check now also flags indexes led by an expression. `TenantResolutionIT`: the admin host redirects into the platform admin |

**Manual check** (local profile, curl with real hosts via `--resolve`):
- Seeding created the platform admin and demo staff.
- demo1 owner login → dashboard; owner `/driver` → 403; staff list shows only demo1 staff.
- Driver login with `0933 000 002` on the admin login page → `/driver`; driver `/admin` → 403.
- A form signup → `/admin` dashboard, and the new store's subdomain greets with its name.
- Platform admin login → store list includes the new store.
- `demo1.lvh.me/admin` and `lvh.me/platform` → 404.
- `JSESSIONID` cookies are host-only (`lvh.me` and `admin.lvh.me` separate).
- Arabic form input from Git Bash's curl arrives as `?` because of the shell's argument encoding; a UTF-8 body file stores it correctly.

### Decisions and deviations

- **Login asks for the store address** (decided 2026-10-14). SPEC 10 says "email or phone". Adding the store keeps authentication inside RLS, keeps email and phone unique per store rather than globally, and lets one person own several stores.
- **Platform pages under `/platform`** on `admin.<base>`, because Spring MVC maps paths, not hosts, and `/` belongs to the storefront.
- **Staff management (list, add, enable, disable) was built in M2**, because a driver account needs it. Editing, password reset and invitations come later.
- **Form-backing objects are JavaBeans** (`SignupForm`, `StaffForm`), not records: Thymeleaf's `th:field` binds through getters and setters.
- **`LocalizedText` omits null languages** in its JSON.
- **Dependencies added:** `spring-boot-starter-security`, `thymeleaf-extras-springsecurity6`, `bucket4j_jdk17-core` 8.14.0, `caffeine` (moved up from M5), `spring-security-test`.

### Notes for later milestones

- **Number formatting:** `MessageFormat` renders `{0}` numbers with Arabic-Indic digits in the `ar` locale (the platform store count shows "٣"). Decide on digits before prices (M4/M5) and format money explicitly.
- **HTMX and expired sessions:** an HTMX request after the session ends gets the login redirect and would swap the login page into a fragment. Handle `HX-Redirect` when admin HTMX arrives (M3).
- **Proxy:** rate limits use `getRemoteAddr()`. Behind a reverse proxy, configure forwarded headers (M15), or every client shares one address.
- **Rate-limit state is per instance and in memory;** that's fine for a single instance.
- **Shopper login (OTP, M8)** will need its own chain or rules inside the site chain, which currently has no authentication.
- **New platform tables** go in `RlsCoverageIT.PLATFORM_TABLES`. New tenant tables call `enable_tenant_rls`.

### Next: M3, catalog and media

Categories, products, options and variants, image upload to MinIO with WebP resizing, admin CRUD with HTMX.
