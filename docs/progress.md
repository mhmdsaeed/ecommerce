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

## M3: Catalog and media (done, 2026-10-14)

**Done when:** a merchant creates a product with 2 variants and images. Confirmed by `CatalogIT.merchantCreatesAProductWithTwoVariantsAndImages` through the admin UI (forms plus HTMX), and live against docker-compose MinIO. `./mvnw verify` is green with 275 tests.

### What was built

- **`media` module (new):**
  - `ImageStore` API with `ImageSize` (SMALL 320, MEDIUM 640, LARGE 1024: the §9.5 `srcset` widths) and the `ImageDiscarded` event.
  - `ImageProcessor`:
    - sniffs the real format from the bytes (JPEG, PNG and WebP only; a renamed text file or a GIF is rejected);
    - checks size (5 MB) and pixel count (100 MP) before decoding;
    - decodes big photos with subsampling, so at most about 2048 px is held in memory, and limits concurrent decodes with a semaphore, since virtual threads would otherwise allow unbounded parallel decoding;
    - turns photos upright from EXIF orientation 1 to 8, then re-encodes to lossy WebP at quality 0.8 (`webp-imageio` native binaries for Windows, Linux and macOS), which drops all metadata, GPS included;
    - downscales in halving steps and never upscales.
  - `S3ImageStore` writes `public/<tenant>/<uuidv7>/<width>.webp` with `Cache-Control: public, max-age=31536000, immutable`. If storage fails mid-upload, the parts already written are removed.
  - `ImageDiscarded` is handled by an `@ApplicationModuleListener` after commit. It is persisted in the event publication registry and republished on restart if storage was down.
  - `BucketSetup` (when `app.media.manage-bucket=true`, local and test) creates the bucket and a public-read policy limited to `public/*`. It logs instead of failing when MinIO is down. Production buckets are managed outside the app.
- **`catalog` module (new):**
  - `Categories`: two levels, sort order, show or hide; categories with products or subcategories can't be deleted.
  - `Products`: search with pagination, create (a DRAFT with one default variant), update, status, delete (drafts only), options and variants.
  - `ProductImages`: add, remove, reorder; at most 10 per product.
  - **Variants (Shopify model):** every product has at least one variant. `VariantMatrix` parses up to 3 options with comma lists (the Arabic comma `،` too) and generates at most 100 combinations. Variants whose combination still exists keep their id and price; new ones start at the first variant's price. Removing all options goes back to one default variant.
  - **Prices** are typed as text and parsed by `shared.Amounts`: `150,000`, `١٥٠٬٠٠٠` and `١٢٫٥` are accepted; the European `12,50` is rejected rather than read as 1250. They are stored as `numeric(19,2)` in the product's currency and exposed as `shared.Money`.
  - **Slugs** may be Arabic (`قميص-قطني`). Diacritics and tatweel are dropped, names are deduplicated with `-2`, `-3`, and slugs are unique per store.
  - **Publishing** needs an available variant priced above zero. A published product must keep one.
  - **Uploads** are processed outside any transaction (seconds of work); the row is inserted afterwards, and the image is discarded again if that fails.
- **Migration `V5__catalog.sql`:**
  - Tables `category`, `product`, `product_option` (`choices`), `product_variant` and `product_image`. All are tenant tables with `enable_tenant_rls`.
  - **Composite foreign keys `(tenant_id, <parent>_id)`** against `UNIQUE (tenant_id, id)`, so the database itself rejects any reference into another store: a third isolation layer below Hibernate and RLS.
  - Product children cascade on delete; `product_image.variant_id` uses `ON DELETE SET NULL (variant_id)` (PostgreSQL 15+).
  - A generated `product.search_text` column for admin search.
- **`shared`:**
  - `Money` (two decimals; sub-cent amounts are rejected, not rounded).
  - `Amounts`.
  - `Htmx` (`isHtmx`, `HX-Redirect`).
  - `FieldProblem` moved here from `identity`.
  - `Digits`, shared by `PhoneNumbers` and `Amounts`.
- **HTMX and sessions** (the gap noted in M2):
  - HTMX requests in the staff chains with no session get 401 with `HX-Redirect: /admin/login?expired`.
  - CSRF failures on HTMX requests (an expired session has no token) get 403 with the same redirect.
  - `StaffSessionFilter` ends sessions the same way.
  - `app.js` shows a localized banner when an HTMX request fails, since htmx ignores 4xx and 5xx responses.
- **Merchant admin UI:**
  - Categories: one HTMX section with the list and add form, plus an edit page.
  - Products: live search and status filter (HTMX, URL pushed into history), pagination, thumbnails, "from" prices.
  - New product page.
  - Product edit page: status actions (publish, archive, back to draft, delete drafts with an Alpine confirm); an images section (multi-file HTMX upload with one outcome line per file, reorder, remove); an options and variants editor (HTMX; errors keep what was typed); and a details form.
- **Upload limits:** multipart allows 10 MB per file and 60 MB per request as a coarse guard; the 5 MB rule per image comes from the processor with its own message. Tomcat swallows up to 64 MB, so an oversized request still gets a response. Requests over the cap reach HTMX as 200 with `HX-Retarget: #upload-error` (`UploadLimitAdvice`), so the images on the page stay put.
- **Local seed** (`LocalDemoCatalog`, once per empty store): demo1 gets 2 categories; a shirt with Size × Colour (6 variants) and an image; a USD mug with an image; and a draft teapot. All are created through the services, with images generated in Java.
- **Config:**
  - `app.media.*` from `S3_*` (plus `S3_REGION`, `S3_MANAGE_BUCKET`).
  - `hibernate.default_batch_fetch_size: 32` for the product list.
  - `spring.modulith.events.republish-outstanding-events-on-restart: true`.
- **Dependencies:** AWS SDK v2 `s3` 2.55.16 (synchronous client only; netty excluded), `webp-imageio` 0.11.0, `metadata-extractor` 2.21.0; for tests, `testcontainers-minio` and `awaitility`.

### Tests (275, all green: 159 unit, 116 integration)

| Test | Checks |
|---|---|
| `CatalogIT` (17) | The done-when flow: create (`150,000`) → options `S، M` → prices with Arabic digits and compare-at and SKU → upload JPEG + PNG → publish → list shows "from 150,000" and thumbnails. Then: 3 renditions per image in MinIO (`image/webp`), anonymously readable with the immutable cache header. Also: adding a value keeps existing variant ids and prices; removing options returns to one default variant; option errors keep typed values and the variant table; variant errors show on their row; non-images rejected with nothing stored; uploaded file names escaped; removing an image deletes its renditions (awaited); reorder; publishing needs a price; only drafts can be deleted; Arabic slugs deduplicated; new-product validation; HTMX search (with `%` and `_` escaped); categories (nesting depth, in-use deletes refused, hide); another store gets 404 on view, status, options and upload, and changes nothing; HTMX with an expired session or a stale CSRF token gets `HX-Redirect` |
| `UploadLimitIT` | Real server, since MockMvc skips Tomcat's limits: an 11 MB upload over HTMX → 200, `HX-Retarget: #upload-error`, localized message |
| `ImageProcessorTest` (10) | JPEG to 320/640/1024 WebP; PNG with alpha; WebP input; no upscaling; EXIF orientation 6 in a hand-built APP1 segment turns landscape into portrait; pixel transforms for orientations 1, 2, 3, 6 and 8; GIF, SVG, text and empty input → unsupported; truncated JPEG → unreadable; size and pixel limits; a 6000×4000 photo via subsampling |
| `VariantMatrixTest`, `SlugsTest`, `AmountsTest`, `MoneyTest` | Option parsing and combinations; Arabic and Latin slugs; amount formats including the European `12,50` rejected; `Money` scale and equality |
| `RlsCoverageIT` | Now covers `category`, `product`, `product_option`, `product_variant` and `product_image` (forced RLS, single policy, `tenant_id` NOT NULL, indexes leading with `tenant_id`) |

**Live check** (local profile, docker-compose MinIO, curl on real hosts with HTMX headers and the page's CSRF token):
- Bucket created and demo catalog seeded.
- 3000×2000 JPEG + PNG uploaded in 1.5 s; the 640 rendition is a 4 KB WebP served by MinIO with the immutable cache header.
- Six variant prices set with `١٥٥٬٠٠٠` → `155000.00 SYP`.
- Arabic HTMX search returns only the fragment.
- Probes:
  - fake `.jpg` → "not a JPEG, PNG or WebP";
  - 6 MB → "larger than 5 MB";
  - 12 MB → retargeted message (fixed during the check; it had been a silent 413);
  - HTMX without CSRF → 403 + `HX-Redirect`; without a session → 401 + `HX-Redirect`;
  - demo2's owner on demo1's product → 404, upload → 404;
  - removing an image → its rendition URL returns 404 within a second.

### Decisions and deviations

- **MinIO host port moved to 9100 (console 9101)**, configurable with `MINIO_PORT` / `MINIO_CONSOLE_PORT`. On this machine `ipqservice` binds `127.0.0.1:9000`, which wins over Docker's wildcard bind for IPv4 clients. The JVM got "server failed to respond" while curl (over IPv6) worked. Same pattern as Postgres on 5433. After pulling, run `docker compose up -d minio` (data is in the named volume).
- **Option values are identified by label** (Arabic, else English). Renaming a value creates a new variant with the default price, rather than renaming the old one.
- **Variant ↔ image link** (`product_image.variant_id`) is in the schema but has no UI yet. Alt text is likewise not editable yet.
- **Admin amounts use Western digits** (`MoneyText`); store-level digit and rounding settings come with M4.
- **Thymeleaf 3.1 restricts expressions in URL attributes:** bean calls go through `th:with` first (`th:with="url=${@imageUrls.small(key)}" th:src="${url}"`).
- **Conditions vs. test properties:** `@ConditionalOnProperty` is evaluated before Testcontainers registers its properties (like Flyway in M1), so `BucketSetup` checks its flag at run time.
- **Spring Modulith's documenter** parses generated Javadoc JSON with Boot's `BasicJsonParser`, which failed on one comment mixing `{@code ${…}}` with a quoted `"150,000"`. The comment was reworded and the trap documented on `MoneyText`.
- **All merchant-admin roles** (owner, manager, staff) can manage the catalog.

### Notes for later milestones

- **M5 storefront:** use `ImageStore.url(key, size)` for `srcset` (320/640/1024) with `width`/`height` from `product_image` (no layout shift); add a trigram index on `product.search_text`; only show ACTIVE products in active categories.
- **M4 pricing:** `Money` exists; add conversion, rounding and digit settings, and use them in `MoneyText`.
- **M6 inventory:** `stock_level` per variant. Variants removed by option changes are deleted outright today; once orders reference them (M8), deactivate instead.
- **M8 orders:** keep product snapshots on `order_item` so later catalog edits never change past orders.
- **Orphaned objects:** an upload whose row insert fails is discarded, but a crash between upload and insert leaves objects behind. Add a sweep with the M11 jobs.
- **Production (M15):** set `S3_MANAGE_BUCKET=false`, create the bucket and its `public/*` read policy (or a CDN in front). The `webp-imageio` natives need glibc, so not an Alpine image.

## M4: Pricing (done, 2026-10-14)

**Done when:** unit tests for conversion and rounding pass. `CurrencyConversionTest` (28 cases) passes.

### What was built

- **`pricing` module (new):**
  - `CurrencyConversion`: pure arithmetic, so an order can repeat a conversion from its rate snapshot.
    - The rate is pounds per dollar.
    - To SYP: multiply, then apply the store's `RoundingRule`.
    - To USD: divide and round half up to cents.
    - A positive price never converts to zero; a free item stays free.
    - A price already in the target currency is returned untouched; merchants' own prices are never rounded.
  - `ExchangeRates`: append-only USD→SYP history. The current rate is cached per store for 60 s, and setting a rate evicts the entry. Rates are typed like amounts, with up to 6 decimals (`١١٨٫٥` is accepted). Zero, negative, ambiguous `12,5` and above 1,000,000 are rejected.
  - `Prices.display(Money)`: a price in the store's display currency, plus the rate used (null when nothing was converted). Without a rate, the price shows in its own currency.
  - `PriceFormatter`: grouped digits in the locale's numbering system; decimals only when there are any; currency placed by the bundle (`price.format.SYP={0} ل.س`, `price.format.USD=${0}` in English).
- **`shared`:**
  - `RoundingRule` (NONE, NEAREST_5/10/100; half up to the nearest step; never rounds to zero).
  - `NumberDigits` (WESTERN, ARABIC_INDIC via the Unicode `nu` locale extension).
  - `LocaleAdjuster` hook; `LocaleConfig` now wraps the cookie resolver and runs adjusters on every resolve.
  - `Amounts.parse(input, maxDecimals)`.
  - A `Clock` bean.
- **Digits everywhere:** the tenant module's `StoreSettingsService` is a `LocaleAdjuster`: the request locale becomes `ar-u-nu-latn` or `ar-u-nu-arab`. Every `NumberFormat` and `MessageFormat` on the page follows (prices, counts like "الصور (٢ من ١٠)", the rate line), while the language, bundles and text direction stay the same. Pages without a store (signup, platform admin) use Western digits. Form inputs keep plain digits, and the parsers accept both.
- **`tenant`:** a `StoreSettings` API (base currency, display currency, rounding rule, digits), cached for 60 s and evicted on update.
- **Migration `V6__pricing.sql`:**
  - `store.rounding_rule` (default NEAREST_5) and `store.number_digits` (default WESTERN), with CHECKs.
  - `staff_user UNIQUE (tenant_id, id)`.
  - `exchange_rate` tenant table with RLS, `rate > 0`, the composite foreign key `(tenant_id, set_by)` to staff, and an index for the current-rate lookup.
- **Merchant admin:**
  - Settings → Prices and currency (owners and managers; `@PreAuthorize` on the controller):
    - display and base currency, rounding rule and digits (post/redirect/get);
    - an HTMX rate section with the current rate ("1 USD = 118.5 SYP since …, set by …"), a set-rate form, a preview of 5 sample conversions under the current rule, and history (last 10, names from staff).
  - A dashboard nudge for owners and managers while no rate is set.
  - New products default to the base currency.
  - Product list prices go through `PriceFormatter` (`@money.format`).

### Tests (325, all green: 202 unit, 123 integration)

| Test | Checks |
|---|---|
| `CurrencyConversionTest` (28) | USD→SYP under each rule (e.g. $4.50 × 118.5 = 533.25 → 535 / 530 / 500); half-up boundaries per rule; never rounds to zero (in both directions); free stays free; SYP→USD to cents (1,000 → $8.44; the pound rule never applies to dollars); prices in the target currency untouched; large amounts exact; invalid rates refused |
| `PriceFormatterTest` | `150,000 ل.س` / `١٥٠٬٠٠٠ ل.س` / `12.50 $` / `١٢٫٥٠ $` / `$4.50` / `$25` / `150,000 SYP`; decimals only when present; rates with their own decimals |
| `NumberDigitsTest`, `AmountsTest` | `nu` extension only (language unchanged), `MessageFormat` follows it; rates up to 6 decimals |
| `PricingIT` (7) | Setting rates over HTMX (fragment, preview 535 / 120 / 126.58 $, history with the setter's name, Arabic-digit input); invalid rates; `Prices.display` with and without a rate and under NEAREST_100; switching to Arabic-Indic changes the product list and counts while inputs stay plain; another store keeps its own settings and has no rate; dashboard nudge and the base-currency default; STAFF gets 403 |
| `RlsCoverageIT` | Covers `exchange_rate` |

**Live check** (local profile): dashboard nudge shown → rate `١١٨٫٥` set over HTMX → "1 دولار = 118.5 ل.س" with the preview $1 → 120, $4.50 → 535, $25 → 2,965, 1,000 ل.س → 8.44 $ → nudge gone. The product list went from `95,000 ل.س | 4.50 $ | 155,000 ل.س` to `٩٥٬٠٠٠ ل.س | ٤٫٥٠ $ | ١٥٥٬٠٠٠ ل.س` after switching digits; the NEAREST_10 preview showed 2,960. One inconsistency was found and fixed: the literal "1" in "1 دولار =" stayed Western; it's now a formatted argument, with an assertion added.

### Decisions and deviations

- **Defaults: NEAREST_5 rounding and Western digits.** Unrounded conversions show odd amounts like 532.13 ل.س, and Western digits are the norm for prices on Levantine e-commerce. Both are per-store settings.
- **Rounding is half up to the nearest step**, as SPEC 7 says "nearest". A merchant who wants to protect margins would want "round up"; that could be added as a rule later.
- **Only conversions are rounded.** A price the merchant typed in the display currency is shown exactly as typed.
- **USD amounts in Arabic read `12.50 $`.**
- **Digits through the locale**, not per-call options, so nothing on a page can disagree. `LocaleAdjuster` keeps `shared` free of module dependencies.
- **Spring Modulith's documenter** broke again on Javadoc, this time on commas inside quoted text and between code tags. The rule is noted on `PriceFormatter` and `MoneyText`. `ModularityTests` catches it in seconds.

### Notes for later milestones

- **M5 storefront:** use `Prices.display` + `PriceFormatter` for every shown price. `compare_at_price` converts the same way.
- **M8 orders:** store `rate_snapshot = DisplayPrice.rate()` and both totals. Re-render past orders with `CurrencyConversion` and the snapshot, never with the current rate.
- **Rate cache:** 60 s per instance. With several instances, a new rate can take up to a minute to show everywhere.
- **Rate staleness:** there's no reminder when the rate is weeks old; consider a dashboard hint (M13).

## M5: Storefront browse (done, 2026-10-14)

**Done when:** a shopper browses the demo store in Arabic and English (`StorefrontIT`, live), and Lighthouse mobile performance ≥ 90: **99–100** on home, category and product (Lighthouse 13.5, mobile emulation, Edge headless; see below).

### What was built

- **Catalog read side** (`StorefrontCatalog`): one visibility rule. A product is visible when it is published, has an available variant, and is uncategorized or in a visible category (active, under an active parent). Hidden categories have no page.
  - Home: navigation and newest products.
  - Category pages include subcategories, paginated (24 per page). Sorting is newest or by price. Price sorting compares across currencies by scaling the foreign currency's lowest price at the store's rate inside the query.
  - Product page: only available variants, and only option values some available variant has.
  - Search: see below.
- **Search** (`V7__storefront_search.sql`): `pg_trgm` (a trusted extension, created by `app_owner`) with a GIN trigram index on `product.search_text`.
  - The column is now normalized for Arabic: hamza forms of alef → ا, ى → ي, ة → ه, diacritics and tatweel dropped. `ProductSearch` normalizes queries the same way, so احمر finds أحمر and مدرسه finds مدرسة.
  - Results are ranked by `similarity()`; `%` and `_` in queries are escaped.
- **Storefront pages** (`storefront` module): `/`, `/c/{slug}`, `/p/{slug}`, `/search`, `/lang/{ar|en}`.
  - `/lang/{ar|en}` sets the cookie and returns to the referring page on the same host only, or `/`.
  - The product page's Alpine `variantPicker` reads the available variants and their displayed prices from `data-variants` (JSON escaped by Thymeleaf) and shows the chosen combination's price, compare-at price, or "not available".
  - Prices go through `Prices.display` and `PriceFormatter` (`@shopPrices`).
  - The main site's `/` stays the platform placeholder; storefront paths there answer 404.
- **Language:** with no `lang` cookie, the store's default language applies (SPEC 11). This uses a new `shared.DefaultLocaleSource` hook implemented by the tenant settings service, alongside the digits adjuster from M4.
- **Theme** (SPEC 10, settings only): `store.theme` JSON as `StoreTheme`, edited at Settings → Storefront (owners and managers):
  - Primary colour (`#rrggbb`, validated): `ThemeStyle` derives brand-50/100/500/600/700 CSS variables and darkens the button shade until white text reaches 4.5:1 contrast.
  - Logo: media upload; a replaced logo is discarded via `ImageDiscarded`.
  - Hero title and text (ar/en; default: "Welcome to {store}").
  - Font: MODERN or CLASSIC system stacks only (no web fonts, per the budget).
  - Toggles for the categories and new-arrivals sections.
- **Performance and caching:**
  - Storefront pages carry no CSRF token, so anonymous visits create no session. This was confirmed live: no `JSESSIONID`.
  - Pages send `Cache-Control: private, max-age=60`. Not shared caches: the language comes from a cookie.
  - CSS and JS have content-hashed URLs, cached for 365 days and public. The local profile disables this caching.
  - Images: `srcset` 320/640(/1024), intrinsic `width`/`height`, the first product image eager with `fetchpriority="high"`, the rest lazy.
  - `app.js` now loads before Alpine: Alpine starts as soon as its script runs, so components must be registered first.

### Budget (SPEC 9.5), measured on demo1 with production-like caching and compression

| | Budget | Measured |
|---|---|---|
| HTML per page (gzip) | 50 KB | 1.7–1.9 KB (home, category, product, search) |
| CSS (gzip) | 30 KB | 5.2 KB |
| JS total (gzip) | 40 KB | 37.7 KB (Alpine 19.9, htmx 16.9, app 0.9) |
| Product image | WebP srcset, lazy | 640 rendition 5 KB WebP; LCP image eager + high priority |

### Lighthouse (mobile, simulated throttling; local profile with production caching and compression)

| Page | Performance | Accessibility | Best practices | SEO | LCP | TBT | CLS |
|---|---|---|---|---|---|---|---|
| Home | 99 | 100 | 78 | 91 | 1.4 s | 0 ms | 0 |
| Category | 100 | 100 | 78 | 91 | 1.3 s | 0 ms | 0 |
| Product | 100 | 100 | 78 | 91 | 1.4 s | 0 ms | 0 |

The first run found three things, fixed before the run above:
- **Contrast:** the platform's default brand-600 (#008b61) gave white button text only 4.31:1. It's now #00855d (4.65:1), with 700 at #006a4a. Merchant colours were already darkened by `ThemeStyle`; the built-in default never went through that check.
- **A 404 for `/favicon.ico`:** there's now an empty icon link.
- **Heading order** on category and search pages (`h1` straight to the `h3` product names): a visually hidden `h2` was added.

What remains:
- Best practices 78 and SEO 91: HTTPS and the http→https redirect (production, M15), and a meta description (M13).
- One cold-JVM `server-response-time` warning on the first hit after restart.

Run with a portable Node.js 24 and `npm install lighthouse` in the session scratchpad. With `NODE_OPTIONS=--use-system-ca` (TLS interception), `CHROME_PATH` set to Edge, and `--chrome-flags="--headless=new --host-resolver-rules=\"MAP *.lvh.me 127.0.0.1\""`.

### Tests (352, all green: 219 unit, 133 integration)

| Test | Checks |
|---|---|
| `StorefrontIT` (10) | Arabic browse: RTL, welcome, visible categories only, published products only (draft, hidden-category and no-available-variant products absent), $4.50 → 535 ل.س, "from" prices, category with subcategory, product page with options and variant JSON prices. English browse via cookie. Store default language without a cookie. `/lang` returns to the same-host page; foreign referrers go to `/`. Price sort interleaves USD and SYP products correctly in both directions. Search with Arabic normalization (احمر → أحمر), case-insensitive Latin, hidden products not found, wildcards safe. Hidden or missing pages → 404, also on the main site. Cache header and no session or CSRF on every browse page. Theme colour, font and hero applied. Admin theme form saves, and an injected colour value is rejected |
| `ProductSearchTest`, `ThemeStyleTest` | Normalization mirrors the SQL column; LIKE escaping; shade derivation; light colours darkened to ≥ 4.5:1; classic font stack |
| `PlaceholderPageIT` | Now expects content-hashed asset URLs |

### Decisions and deviations

- **Browser-only caching (`private`)** because the language is a cookie. CDN caching (§9.5) would need the language in the URL, or `Vary: Cookie` (poorly supported by CDNs). Revisit with production hosting (M15).
- **No CSRF on storefront pages until the cart (M6).** The cart needs a token without forcing a session per visitor: either a cookie-based CSRF repository for the site chain, or tokens only on cart and checkout pages.
- **htmx is loaded on browse pages** although nothing uses it until M6. It's within budget (37.7/40 KB), but there is little room left. Loading it only on pages that use it would free 17 KB.
- **The theme colour input can't be empty**, so the form starts from the platform green (#13896a). There's no "reset to default" yet.
- **Hiding a category hides its products everywhere** (pages, search, home), not just its page.

## M6: Cart and inventory reservations (done, 2026-10-14)

**Done when:** the cart works via HTMX (`CartIT` with the real CSRF cookie, and live in headless Edge on demo1); stock cannot go negative under a concurrent test (`InventoryIT`: 40 threads racing for 10 units, exactly 10 win).

### What was built

- **Inventory module** (`V8__inventory_and_cart.sql`, `stock_level`): stock per variant, `on_hand` and `reserved`.
  - A variant without a row is **not tracked** and can always be bought. That keeps existing catalogs working and suits merchants who don't count stock.
  - `Inventory.reserve` is all or nothing; also `release`, `deduct` (on delivery, from M8) and `setOnHand` (null stops tracking).
  - Every change is a single conditional `UPDATE … WHERE on_hand - reserved >= :qty`. PostgreSQL re-checks the condition on the row it locks, so concurrent orders cannot oversell. Variants are updated in id order (no deadlocks).
  - A failed multi-variant reservation undoes its own partial updates instead of marking the transaction rollback-only, so a caller's transaction (order placement, M8) stays usable.
  - Table checks back this up: `on_hand >= 0` and `0 <= reserved <= on_hand`.
  - Lowering stock below what is reserved, or untracking while reserved, is refused.
  - SQL names `tenant_id` explicitly on top of RLS.
- **Cart module** (`cart`, `cart_item`): carts found by a random 256-bit token in an HttpOnly cookie. The table stores only its SHA-256.
  - Carts hold variants and quantities only. Names, prices (display currency, store rounding) and availability are looked up on every read, so the cart shows what checkout would charge now.
  - What may be bought comes from the new `StorefrontCatalog.offers`, which applies the same visibility rule as the pages.
  - Rules:
    - quantities are capped by stock and 99 per line, with at most 50 lines;
    - adding asks: sold out, limited, unavailable, cart full;
    - lowering a quantity is always allowed;
    - unavailable lines stay visible, with a remove button, and are left out of the totals.
  - Changes lock the cart row (two tabs apply in turn). A cart lives 30 days after its last change.
  - `CartExpiryJob` deletes expired carts hourly, store by store inside each tenant context. It uses Spring `@Scheduled` until JobRunr arrives (M11), and is disabled in tests via `app.scheduling.enabled`.
  - Without an exchange rate, foreign-currency prices can't be converted; totals then show one amount per currency.
- **Storefront** (SPEC 9.2): `GET /cart`, `POST /cart/items`, `PATCH /cart/items/{id}` (0 removes), `DELETE /cart/items/{id}`.
  - HTMX requests get fragments: a status line on the product page, the whole cart on the cart page. Each change sends `HX-Trigger: cart-changed`. Cart responses are `no-store`.
  - Product page: quantity and "Add to cart". The variant picker starts on the first variant in stock, marks sold-out combinations and disables the button.
  - Header badge: the count comes from a readable `cart_count` cookie (Alpine `cartBadge`), refreshed on `cart-changed` and on back/forward navigation. So browse pages stay identical for everyone, and the badge costs no query.
- **CSRF without sessions** (resolves the M5 open point):
  - Storefronts get their own security chain with `CookieCsrfTokenRepository`. The token lives in a readable `XSRF-TOKEN` cookie, issued on the first storefront page.
  - `app.js` copies it into `X-XSRF-TOKEN` on HTMX requests (double submit). Pages never contain the token, and still create no session.
  - The main site and admin keep session tokens.
- **Admin inventory page** (`/admin/inventory`, all admin roles):
  - lists every variant with search by name or SKU;
  - saves per row over HTMX, accepting Arabic digits and grouping;
  - shows reserved and available;
  - an empty field stops tracking.
- **Local seed:** `LocalDemoStock` tracks every other demo1 variant (5 on hand, the first one sold out).

### Verified live (demo1, headless Edge via puppeteer-core, production-like caching)

- **First page:** sets only `XSRF-TOKEN` (no session), and the badge is hidden.
- **Product page:** the picker chose the first in-stock variant. Adding 2 answered "تمت الإضافة إلى سلتك." and the badge became 2.
- **Cart page:** the line shows options, unit price and total.
  - "+" changed the line to 3 and the badge to 3.
  - "Remove" emptied the cart and hid the badge.
- **Errors:** no failed requests and no JS errors.
- **Headers:**
  - `/cart` sends `Cache-Control: no-store`;
  - a POST without the token gets 403.
- **Bug found and fixed:** demo products have photos, and calling a bean inside `th:src` is forbidden by Thymeleaf, so `/cart` failed with images. The URL is now computed in `th:with`, and `CartIT` adds image rows to cover it.
- **JS budget:** 38.2 KB gzipped (Alpine 19.9, htmx 16.9, app 1.4) of 40.

### Tests (378, all green: 222 unit, 156 integration)

| Test | Checks |
|---|---|
| `InventoryIT` (10) | **Concurrency:** 40 threads × 1 unit against 10 → exactly 10 reserved. 12 × 3 against 10 → 3 succeed, 1 left. Reservations racing with a merchant lowering stock never leave reserved > on hand. **All or nothing:** a shortage on one variant reserves nothing. **Rules:** untracked variants always reserve; release and deduct; releasing more than reserved fails loudly; on hand can't go below reserved, and tracking can't stop while reserved; bad input; unknown variant. **Isolation:** another store can't see or change the stock |
| `CartIT` (10) | Full HTMX flow with the real CSRF cookie: add (fragment, `HX-Trigger`, cookies), mixed-currency total (2 × 1,000 + $4.50 → 2,535 ل.س), cart page and fragment with images. Change and remove lines. Stock limits: sold out, partial add, raise refused, stock drops below the cart. Archived and unknown items. Missing or forged token → 403. CSRF cookie issued once, never in the page, no session. Cookie flags, and only the token hash stored. A token is useless on another store. Expired carts look empty and are deleted. Sold-out flag in the product JSON |
| `InventoryAdminIT` (3) | List and HTMX search, saving with Arabic digits (١٢ → 12), untracking, invalid and below-reserved messages, unknown variant → 404 |
| `CartTokensTest` (3) | Unique, well-formed tokens; tampered tokens rejected; hashing |

### Decisions and deviations

- **No reservation at add-to-cart.** SPEC 6.4 reserves at order placement. The cart only checks stock, so abandoned carts never hold stock. Two shoppers can both have the last unit in their carts; the first to order gets it (M8 must show a clear message to the other).
- **No `currency` column on `cart`** (SPEC 5 lists one): carts store no prices, so there's nothing to tag. The order snapshots currency and rate (M8).
- **`customer_id` on `cart`** is deferred to M8 (OTP login), when carts can merge into a customer's.
- **Cart edits need JavaScript.** The product picker needs it anyway, and plain form fallbacks would put the CSRF token into page HTML.
- **No sold-out badge on product cards** in listings yet; only the product page knows stock. Add it if merchants ask (it needs inventory in the catalog queries).
- **Stock is changed through SQL, not JPA:** `StockLevel` is read-only in practice. Optimistic locking of the entity is replaced by conditional updates, which is stronger for this case.
- **Variant deletion** (redefining options) cascades to stock and cart lines. Once orders exist (M8), deleting a variant with reservations must be blocked.

## M7: Delivery zones and customer addresses (done, 2026-10-14)

**Done when:** zone matching tests pass (`ZoneMatcherTest`, plus matching through the database in `DeliveryZonesIT`); the checkout fee is computed (`FeeRulesTest`, `DeliveryQuotes` in `DeliveryZonesIT` and `CustomerIT`, and shown under the cart in `DeliveryEstimateIT` and live).

### What was built

- **Migration `V9__delivery_and_customers.sql`:** three tenant tables, all with `enable_tenant_rls`.
  - `delivery_zone`: governorate (CHECK against the 14), optional city, optional area (needs a city), fee, currency, optional `min_order` and `free_over`, `eta_text` jsonb, active.
    - `city_key` and `area_key` hold the names folded for matching.
    - `UNIQUE NULLS NOT DISTINCT (tenant_id, governorate, city_key, area_key)`: one zone per destination, including whole-governorate zones.
  - `customer`: phone in E.164, unique per store; name, blocked, block reason, order and refusal counts.
  - `customer_address`: governorate, city, optional area, landmark and/or details (at least one), optional phone, optional lat/lng pair (range checks). It has a composite foreign key to its customer.
- **`delivery` module (new):**
  - `Governorate` enum and `Destination` (governorate, city, area; names trimmed, `key()` folds them).
  - `DeliveryZones`: list, find, create, update, pause/resume, delete; `match(destination)`; `coverage()` (where active zones deliver, for address forms).
  - **Matching** (`ZoneMatcher`, pure): among the active zones of the governorate, an area zone beats a city zone, which beats a whole-governorate zone. Names match after folding: hamza forms, ta marbuta, alef maksura, diacritics, tatweel, case and spaces, so "المزه" finds "المزة".
  - **Fees** (`FeeRules`, pure):
    - zone amounts go through `Prices.display`, so a dollar zone shows in pounds at the store's rate and rounding, like product prices;
    - below the minimum gives `BelowMinimum` (with the shortfall); at or above free-over, delivery is free;
    - `Deliverable` carries fee, total, "add X for free delivery" and the ETA;
    - thresholds that cannot be converted (no rate yet) are not applied, so a missing rate never blocks delivery.
  - `DeliveryQuotes.quote(destination, subtotal)` goes through the SPEC 8 SPI: a sealed `ShippingProvider` with its one implementation `MerchantDeliveryProvider`.
- **`customer` module (new):** `Customers` with `register(phone, name)` (any Syrian spelling; `INSERT … ON CONFLICT DO NOTHING`, so concurrent checkouts with one new phone share the customer) and add, update, remove and list addresses, at most 10 per customer. Validation reports field problems. `AddressView.destination()` feeds the quote. There is no UI yet: checkout and OTP (M8) create customers.
- **`shared`:** `ArabicText.fold` (the search normalization from M5, now shared; `ProductSearch` delegates to it). `Money.plus`, `minus`, `isLessThan` and `sameCurrency` refuse to mix currencies.
- **Merchant admin, Delivery** (`/admin/delivery`, owners and managers; the nav link only shows for them):
  - the list shows destination and scope, fee or "free", minimum, free-over and ETA, and status;
  - the add form swaps the section over HTMX; editing uses its own page with post/redirect/get; pause/resume and delete (with confirm) also use HTMX;
  - amounts accept Arabic digits and grouping, and new zones default to the base currency;
  - errors stay on their fields, and a duplicate destination (in any spelling) gets a form-level message.
  - `FormErrors` moved up to `merchantadmin.internal.web` to be shared.
- **Storefront cart, delivery box** (only when the store has active zones and the cart has items):
  - governorate select (only governorates the store delivers to), plus city and area inputs with `<datalist>` suggestions taken from the zones;
  - changes ask `GET /cart/delivery` over HTMX (no CSRF needed). The answer is the quote fragment, with the suggestion lists swapped out of band, so the inputs keep focus;
  - the box asks for the next level (city, then area) instead of saying "not delivered" too early, then shows fee or free, ETA, total with delivery, "add X for free delivery", the minimum order shortfall, or "we do not deliver there";
  - the choice is kept in an HttpOnly `delivery_to` cookie (Base64url), so quantity changes keep the quote, and checkout (M8) can start from it. Without JavaScript the form's GET sets the cookie and redirects to `/cart`.
- **Local seed** (`LocalDemoDelivery`):
  - demo1: Damascus city 2,000 (free from 50,000), Mazzeh area 1,500, all of Rif Dimashq 3,500 (minimum 10,000), Aleppo city $5 (minimum $20);
  - demo2: Homs city 2,500.
- **Fix to M6:** the cart controller read the `cart` cookie with `@CookieValue`, which URL-decodes it, so a tampered value like `%%%` answered 500. Both cart cookies are now read raw (`CartCookies.token`), and a malformed token is just no cart.

### Verified live (local profile, curl on demo1)

- V9 applied, and the zones were seeded.
- 4 coffee mugs added (4 × 535 = 2,140). The cart shows the delivery box with Damascus, Rif Dimashq and Aleppo only.
  - Damascus → "enter your city", with دمشق suggested.
  - دمشق / المزه (another spelling) → fee 1,500, "same day", total 3,640, "add 47,860 for free delivery".
  - Rif Dimashq, any city → minimum 10,000, add 7,860.
  - Aleppo → the $20 minimum shown as 2,370 ل.س at the demo rate, add 230.
  - Homs (not covered) → asks for a governorate again.
- Admin `/admin/delivery` as the owner: the 4 zones, with scope, fee (`5 $` for Aleppo), rules and ETA.
- **Found and fixed:** a garbage `cart` cookie gave 500 (see above). It now answers 200 with an empty cart. A garbage `delivery_to` cookie was already safe.

### Tests (422, all green: 245 unit, 177 integration)

| Test | Checks |
|---|---|
| `ZoneMatcherTest` (9) | Area beats city beats governorate, whatever the order; a city zone covers its other areas; a governorate zone covers every city; nothing outside the zones; the same city name in another governorate or the same area name in another city doesn't match; paused zones fall back to broader ones; spelling variants (spaces, diacritics, tatweel, ة/ه, hamza, Latin case) |
| `FeeRulesTest` (7) | Fee plus subtotal; free zone; free from the threshold (49,999 vs 50,000); minimum with shortfall; dollar zone at 118.5 (fee 595, minimum 2,370, free from 4,740); no rate: thresholds skipped and no total; unknown subtotal |
| `DeliveryZonesIT` (8) | HTMX add with Arabic digits and grouping; validation (area without city, European `12,50`, zero minimum, crafted governorate); duplicate in another spelling; edit, pause and delete; STAFF gets 403 and no nav link; another store gets 404 and can reuse the destination; quotes through the database (most specific zone, free over, conversion before and after a rate is set); coverage; `NULLS NOT DISTINCT` refuses a second whole-governorate zone even through raw SQL |
| `CustomerIT` (8) | One customer per phone in any spelling, with the name updated; invalid phone and name; 8 concurrent registrations give one customer; addresses saved (trimmed, phone normalized, coordinates scaled), updated, removed, listed in order; validation; 10-address limit; another customer or store can't touch them, the same phone is a separate customer elsewhere, and the composite foreign key refuses a cross-store address; a saved address priced by its area zone |
| `DeliveryEstimateIT` (4) | Cart box: prompts for governorate, then city; fee, total, ETA, "add 30,000"; area zone; free zone; minimum; not delivered; the cookie keeps the destination across quantity changes, up to free delivery at the threshold; no-JS redirect; crafted governorate, script in the city and a garbage cookie handled; no box without zones or items |
| Unit | `ArabicTextTest`, `CartCookiesTest` (round trip, tampering), `MoneyTest` (arithmetic, mixed currencies refused) |
| `CartIT` | New: a malformed cart cookie is an empty cart, not a 500 |
| `RlsCoverageIT` | Covers `delivery_zone`, `customer` and `customer_address` automatically |

### Decisions and deviations

- **Whole-governorate zones** (city left empty) were added to SPEC 8's governorate → city → area. Many Syrian merchants charge one fee per governorate outside their own city.
- **Zone names are free text with folded matching**, not a fixed list of cities and areas: there is no reliable public list for Syria, and merchants name areas the way their customers do. Shoppers get suggestions from the zones but may type anything; an unknown city falls back to a whole-governorate zone if there is one.
- **Fees are converted like prices** (display currency, store rounding), so they add up with the cart. The order (M8) will snapshot the fee with the rate.
- **The minimum order and free delivery compare against the cart subtotal before delivery.** If the subtotal mixes currencies (no rate yet), only the fee is shown.
- **Customers have no admin page yet.** The customer list, blocking and refusal history arrive with order management (M9), where they are used.
- **Deleting a zone is allowed.** Orders will keep a snapshot and `zone_id` (M8); if a foreign key is added then, deleting a used zone should become pausing.

### Notes for M8 (checkout and orders)

- Start the address form from the `delivery_to` cookie, and require an address whose `DeliveryQuotes.quote` is `Deliverable`. Re-quote on submit, because zones may change.
- Snapshot the fee in the charge currency, plus the rate. `Deliverable.zone()` carries the zone's own amounts.
- `Customers.register` on checkout; the counters (`order_count`, `refused_count`) and `blocked` feed the SPEC 6.4 limits.

## M8: Checkout and orders (done, 2026-10-14)

**Done when:** Playwright, a COD order placed and visible in admin. `CheckoutE2EIT` drives Microsoft Edge (Playwright 1.63, Chromium in CI): product page, add to cart, cart, checkout (address, summary refreshed over HTMX), place the order, order page; then the merchant logs in and finds the order in the list and its detail. Inside a green `./mvnw verify` (471 tests).

### What was built

- **Migration `V10__checkout_and_orders.sql`:**
  - `store`: `otp_required` and `new_customer_max_total`.
  - Tenant tables, all with `enable_tenant_rls`: `payment_method_config`, `otp_challenge`, `order_counter`, `shop_order`, `order_item`, `order_event` and `payment_proof`.
  - `shop_order` keeps the snapshot: the totals in the currency charged, the rate used, the address as jsonb, a hash of the tracking token and the idempotency key, both unique per store.
  - `order_item` keeps the product as ordered (name, SKU, options). If the variant is deleted later, `variant_id` becomes null and the line stays.
  - `order_event` is append-only: a trigger refuses UPDATE and DELETE whoever asks. A per-order `position` gives the order of events, since timestamps can tie.
  - CHECKs: total = subtotal + delivery, line total = unit price × quantity, statuses, methods.
- **`order` module (new):**
  - `OrderStatus` and `OrderStateMachine`: exactly the SPEC 6.1 transitions; anything else throws `IllegalOrderTransitionException` and changes nothing.
  - `Orders.changeStatus` locks the order. Stock follows it: cancelled or returned releases, delivered deducts. Delivered and refused orders count on the customer. Each change writes an event and publishes `OrderStatusChanged` (with the tenant id, for listeners in M11).
  - `Checkout.place`, one transaction (SPEC 6.4):
    1. form checks;
    2. the idempotency key, under an advisory lock (a repeated submit returns the first order, with the same tracking link);
    3. phone verification, when the store asks for it;
    4. the cart, locked;
    5. prices recalculated in the display currency;
    6. the delivery quote;
    7. the expected total (the total the page showed; a different one stops the order);
    8. the payment method;
    9. the customer's standing, under an advisory lock per phone: blocked, the new-customer cap, at most 2 open orders until one is delivered;
    10. stock reserved all or nothing;
    11. then the order, numbered 1001, 1002, ... per store, its PLACED event, the address saved to the customer's address book, and the cart cleared.
  - Refusals are typed (`Reason` plus an amount where one is named). Blocked customers get a generic message.
  - The tracking token is derived from the idempotency key (SHA-256). Only its hash is stored, and a repeated submit can rebuild it.
  - `Orders.submitProof`: a transaction reference for a transfer moves the payment to AWAITING_VERIFICATION and writes a PROOF_SUBMITTED event. Refused for COD, for a payment already being checked or paid, and for closed orders.
- **`payment` module (new):**
  - `PaymentMethod` (COD and the four transfer methods), `PaymentStatus`.
  - `PaymentMethods`: per-store settings. COD is on until turned off; transfers need an account number and holder name; the last enabled method cannot be turned off.
  - `Payments`, behind the SPEC 6.3 SPI: a sealed `PaymentProvider` with `CodPaymentProvider` and `ManualPaymentProvider`. It gives the initial status and the instructions shown on the order page.
- **`notification` module (new, minimal):** `Notifications.sendCode` through a sealed `NotificationChannel`, whose only implementation for now is `LoggingChannel`. Message texts come from the bundles.
- **Phone verification** (`customer.PhoneVerification`):
  - 6-digit codes stored as a hash salted with the row id, valid for 5 minutes;
  - 5 guesses per code, serialized by a row lock and compared in constant time;
  - 3 codes per phone per 15 minutes, counted in the database;
  - Arabic digits accepted;
  - a correct code gives a verification token (hash stored) that checkout accepts for 30 minutes, for that phone only.
- **Customers:** `block`/`unblock`, `recordDelivered`/`recordRefused` (SQL increments), `CustomerView.isNew()`.
- **Storefront:**
  - `/checkout`:
    - a plain form with its CSRF token in the page (it is never cached), so placing an order works without JavaScript;
    - the summary refreshes over HTMX when the address changes, with city and area suggestions out of band, and saves the destination for the cart box too;
    - the phone code step runs over HTMX; its token is kept in an HttpOnly `phone_verified` cookie;
    - refusals and field errors show on the page, and the idempotency key is kept for the next submit.
  - `/orders/{number}?t=`:
    - shows status, payment, how to pay (COD amount, or account, holder and instructions for transfers), items, totals and address;
    - has the transaction-reference form;
    - `no-store`, `Referrer-Policy: no-referrer`, and 404 without the right token.
  - The cart's checkout button is live; carts with problems get a hint instead.
- **Merchant admin:**
  - Orders, newest first, paginated, for all roles; the order detail shows items, totals, the rate used, the customer with a `tel:` link, address, notes, proofs and history. Read-only until M9.
  - Settings → Checkout: phone code toggle, new-customer cap. Settings → Payments: one card per method. Both for owners and managers.
  - A shared `dates` bean formats times in Asia/Damascus.
- **Other:**
  - `shared.SecretTokens` (random tokens, codes, hashes, derived tokens); `Digits` is now public.
  - `StorefrontCatalog.Offer` carries the SKU; `Carts.lockContents`/`clear`.
  - Local seed: demo1 accepts Sham Cash.
  - CI installs Playwright's Chromium.

### Verified live (local profile, curl on demo1)

- 2 mugs added; `/checkout` is `no-store` with the CSRF and idempotency fields and both methods.
- Summary for دمشق/المزة: 1,070 + 1,500 (same day) = 2,570.
- POST with Sham Cash and expectedTotal 2570 → 302 to `/orders/1001?t=…`; the cart count dropped to 0.
- The order page shows "حوّل 2,570 ل.س" with account 0944 123 456 and the holder; `no-store` and `no-referrer`.
- Reference TX-778899 → "وصلنا رقم العملية", and the payment status became "بانتظار التحقق من الدفع". A wrong token gets 404.
- Admin, as the owner: the list shows #1001, سارة, 2,570, awaiting confirmation, Sham Cash; the history has the placed event and the proof.

### Tests (471, all green: 259 unit, 212 integration)

| Test | Checks |
|---|---|
| `OrderStateMachineTest` (14) | Every SPEC 6.1 pair allowed or refused; happy path; DELIVERED → CANCELLED refused; open statuses; actor names |
| `CheckoutIT` (19) | COD order: totals, snapshot, event, stock reserved, cart cleared, customer and address kept, numbers 1001/1002, tokens per order. Converted prices with the rate snapshot, unchanged after a new rate. 6 concurrent submits with one key → one order. Two shoppers racing for the last unit → one wins, OUT_OF_STOCK for the other. Field errors. Empty cart, not delivered, below minimum, stock or cart changed, no rate, changed total (then confirmed with the same key), disabled method, blocked customer (generic), new-customer cap and open-order limit (lifted after a delivery). Cancel releases, delivery deducts and counts, refuse and return count and release. Illegal transition changes nothing. The event log refuses UPDATE and DELETE. Transfer proofs. Another store sees and changes nothing |
| `PhoneVerificationIT` (6) | Code logged and verified, token tied to the phone, single use; Arabic digits; 5 guesses then dead; 3 codes per phone; expiry; codes per store |
| `CheckoutWebIT` (9) | No-JS checkout with the real CSRF cookie → order page; repeated submit → same order; 404 without the token; field errors and refusals rendered; crafted method → field error; CSRF and cart required, 404 on the main site; HTMX summary; phone code flow (wrong, right, other phone refused); transfer reference; merchant list and detail, another store gets 404; checkout and payment settings (Arabic digits, invalid cap, account required, last method kept); STAFF gets 403 on settings |
| `CheckoutE2EIT` (1) | The done-when, in a real browser |
| `RlsCoverageIT`, `DatabaseRolesIT` | Cover the 7 new tables |

### Decisions and deviations

- **The charge currency is the display currency.** Shoppers pay what they were shown. `currency` and `display_currency` are both stored (equal for now), with the rate. Carts whose prices cannot be converted (no rate) cannot be ordered.
- **The total the page showed travels with the form.** A different server total (price, rate or fee changed) stops the order and shows the new total; resubmitting confirms it.
- **"New customer" means no delivered order** (`order_count`, counted on DELIVERED). The cap is in the display currency; the open-order limit (2) is fixed.
- **Proof screenshots are deferred.** The proof is the transaction reference for now. Screenshots need private storage with signed URLs (SPEC 10), which comes with M9, where the merchant reviews proofs. QR codes for wallets come with it.
- **`/account/orders` and shopper login are deferred.** Shoppers follow orders through the private link. Saved addresses are kept, but not offered at checkout without a login, since showing them to anyone typing a phone number would leak them.
- **The OTP code step needs JavaScript;** checkout without a code works without it. OTP rate limits are per phone; per-address limits come with production rate limiting (M15).
- **`LoggingChannel` writes codes to the log.** Turn OTP on in production only once an SMS or WhatsApp channel exists (M11).
- **Order numbers come from a per-store counter row.** Concurrent orders of one store queue briefly on that row.
- **Disk space:** the run stalled when drive C: filled up (Docker Desktop hung, Maven could not download). After space was freed, Docker Desktop was restarted (with permission) and the compose services brought back.

### Notes for M9

- The admin order detail needs transitions (`Orders.changeStatus` with `Actor.staff`), proof approval and rejection (`payment_proof.status`, `reviewed_by`), driver assignment (`shop_order.driver_id`), and blocking customers (`Customers.block`).
- Show actor names instead of `customer:<id>` in the history.

## M9: Merchant order management (done, 2026-10-15)

**Done when:** a merchant processes an order end to end.
- `OrderManagementIT.merchantProcessesAnOrderEndToEnd` through the admin pages: confirm, prepare, assign a driver, out for delivery, delivered (stock deducted), completed.
- In Edge, `CheckoutE2EIT.merchantApprovesAWalletPaymentAndCompletesTheOrder`: a Sham Cash order paid with a screenshot, which the merchant opens through its signed link, approves, and takes to completed.
- Live on demo1, see below. `./mvnw verify` is green with 482 tests.

### What was built

- **Migration `V11__order_management.sql`:** `payment_proof.reviewed_at`, with a CHECK that only reviewed proofs have one. Indexes on order status, payment status and driver, for the list filters and the driver's list (M10).
- **`Orders` API:**
  - `list(OrderFilter, page)`: statuses, payment status, and a search by number (with or without `#`), phone (any spelling, Arabic digits, or 4+ of its digits) or name. LIKE wildcards are escaped. Uses a JPA Specification.
  - `counts(since)`: for the dashboard.
  - `changeStatus(…, note)`: the note (a cancellation reason) is kept in the event.
  - `assignDriver`: only active drivers of the store, only open orders; writes a DRIVER_ASSIGNED event.
  - `reviewProof`: approve makes the order PAID, reject makes it REJECTED with a note for the shopper, who may send another proof. Records the reviewer and time; writes PAYMENT_APPROVED or PAYMENT_REJECTED.
  - `submitProof` now takes a transaction reference and/or a screenshot.
  - `OrderView` has `driverId`, `nextStatuses()`, `cancellable()` and `pendingProof()`; events carry their note; proofs carry the screenshot key, status and review note.
- **`identity.StaffDirectory` (new):** names and roles of the store's staff, without contact details, for every staff role (unlike `StaffManagement`, which is for owners and managers). Used to check drivers and to show names.
- **Private images** (SPEC 10), in the `media` module:
  - `ImageStore.storePrivate` re-encodes like public images but keeps one 1024 px rendition under `private/<tenant>/…`. The bucket policy only makes `public/*` readable.
  - `signedUrl` presigns a GET (10 minutes on the order page).
  - `S3_PRESIGN_ENDPOINT` sets the host in signed links when browsers reach storage elsewhere; it defaults to `S3_ENDPOINT`.
- **Wallet QR codes:** `payment_method_config.qr_image_key` is now used.
  - Owners and managers upload one per transfer method (a public image; the previous one is discarded after commit).
  - The order page shows it (640 px) while the payment awaits proof.
- **Customers:** `Customers.search` (phone or name, newest first); `CustomerView` has the block reason.
- **Merchant admin:**
  - **Dashboard:** orders today (since midnight in Damascus), awaiting confirmation, payments to check. Each links to the matching filtered list.
  - **Orders list:**
    - live search with status (in progress by default, all, or one status) and payment filters over HTMX;
    - filters pushed into the URL, so links and back/forward work;
    - a driver column.
  - **Order detail** (each action is a post/redirect/get form; a stale form gets "the order just changed"):
    - a button per allowed next status;
    - cancelling asks for an optional reason;
    - driver select;
    - payment proofs with reference, screenshot (signed link) and decision buttons;
    - block/unblock with a private reason (owners and managers);
    - the history with staff names, "the customer" or "the system", and notes.
    - The `?done=`/`?error=` outcomes are checked against a fixed list before they become message keys, so nothing from the URL is evaluated.
  - **Customers** (`/admin/customers`): search, delivered and refused counts, new or returning, blocked badge with its reason, a link to their orders, block/unblock over HTMX (owners and managers).
  - **Settings → Payments:** QR upload and removal per transfer method.
- **Storefront order page:**
  - the proof form is multipart (reference and/or screenshot, CSRF token in the body);
  - the token is checked before anything is stored, so strangers cannot fill storage;
  - shows the QR code and the store's rejection note.

### Verified live (local profile, demo1)

- Sham Cash order #1002 placed by curl. The screenshot was sent as multipart with `_csrf` in the body → `proof-sent`; without the token → 403. This confirms real Tomcat parses the token from multipart bodies.
- Dashboard: orders today 1, awaiting confirmation 2, payments to check 2.
- The signed screenshot link → 200 `image/webp`; the same object without the signature → 403.
- Owner: approve → confirm → prepare → driver "أبو خالد" → out for delivery → delivered → completed, each `302 ?done=…`. Completing again → `?error=changed`.
- The history reads: placed (the customer), proof sent (the customer), payment approved, confirmed, preparing, driver changed, on its way, delivered, completed, each by "سامر الحلبي".
- **Fixed from this check:** the history said "the customer sent a transaction number" for a screenshot-only proof; it now says "payment proof", like the shopper's message.

### Tests (482, all green: 259 unit, 223 integration)

| Test | Checks |
|---|---|
| `OrderManagementIT` (10) | End-to-end processing with stock and event types; a stale button changes nothing; cancel with reason releases stock and shows to the shopper; screenshot by multipart, filtered list, signed link 200 and unsigned 403, approve, then a second decision refused; reject with a note that the shopper sees, then a new proof; blocking (staff 403, owner OK, checkout refused, unblock over HTMX on the customers page, checkout works again); filters and search (status, name, Arabic-digit phone, `#number` over HTMX, wildcards); wallet QR (upload, bad file, shown on the order page, staff 403); outcomes in the URL are not evaluated; another store gets 404 on every action and cannot assign this store's driver |
| `CheckoutE2EIT` (2) | Adds the wallet flow in Edge: screenshot upload, the merchant views it through the signed link (image loads), approves, five status buttons to completed; the shopper sees completed and paid |
| `CheckoutIT`, `CheckoutWebIT`, `MerchantSignupIT` | Updated for the new API, the "no orders in progress" text and the new dashboard |

**Flaky test fixed:** `CheckoutWebIT` and `PhoneVerificationIT` read the OTP code from the log with a regex taking the first 6 digits of the line. The random test store's name sometimes contains 6 digits, so the full run failed once. The regex now takes the code at the end of the message.

### Decisions and deviations

- **Merchants can do every transition in M9,** including out for delivery, delivered, refused and returned. The driver view (M10) adds the same actions on the driver's phone. COD orders stay UNPAID when the merchant marks them delivered: COD becomes PAID when the driver records the collection (M10, `cod_collection`).
- **All staff roles process orders;** blocking customers and payment settings are for owners and managers.
- **Screenshots are images only** (JPEG, PNG, WebP up to 5 MB, re-encoded); PDF receipts are not accepted. Private images are never deleted for now: they are part of the order record.
- **Signed links last 10 minutes** and are made when the page renders; reload the page for fresh ones.
- **Interface default methods do not run in the service's transaction** (Spring's transaction annotations on the class do not reach them). The 3-argument `changeStatus` is declared on the service for that reason; this broke 5 tests during development.

### Fix after the M9 runtime check (2026-10-15)

- **Bug:** a crafted list URL answered 400 with the error page: `/admin/orders?payment=NOPE`, and likewise an unknown product status, a non-numeric `page` on any list, or an unknown storefront `sort`.
- **Fix:** `shared.ListParams` reads page numbers and enum filters leniently (unknown → no filter, first page, default sort). It is used by the orders, customers, products and inventory lists and the storefront category and search pages.
- **Tests:** `ListParamsTest`, and `LenientListParamsIT` covering all six lists. `./mvnw verify`: 486 tests, green.

### Notes for M10

- Driver list: `shop_order.driver_id` plus the new `shop_order_driver_idx`. Driver actions call `Orders.changeStatus` with `Actor.staff(driverId)`.
- On DELIVERED with COD, record `cod_collection` and set the payment to PAID. The dashboard's third figure (outstanding COD) is still to come.

## M10: Driver view and COD settlement (done, 2026-10-15)

**Done when:** the driver delivers and collects, and the settlement page shows the outstanding balance.
- `DriverDeliveryIT.driverDeliversCollectsAndTheMerchantSettles` through the pages:
  - the driver starts and delivers, collecting 22,000 typed in Arabic digits;
  - the settlement list and the dashboard show 22,000 outstanding with the driver;
  - the owner records 15,000, then the remaining 7,000, and the balance goes to zero; more than the balance, zero, or another currency is refused.
- In Edge at phone size (390×844), `CheckoutE2EIT.driverDeliversAndTheOwnerSettlesTheCash`: the driver logs in at `/driver/login`, starts and delivers; the owner opens the settlement page, sees 22,000 and records it.
- `./mvnw verify` is green with 494 tests.

### What was built

- **Schema (`V12__cod_settlement.sql`):**
  - `cod_collection`: one per order (unique per tenant), with the driver (nullable when the merchant marks the order delivered without a driver), amount > 0 and currency.
  - `cod_remittance`: driver, amount > 0, currency, when, received by (staff) and a note.
  - Both have `tenant_id`, RLS and indexes by driver.
- **Order module:**
  - `DriverDeliveries` (`openFor`, `find`, `startDelivery`, `deliver`, `refuse`):
    - each step locks the order and checks that it belongs to this driver and is at the right step, so a double tap or a merchant acting at the same time cannot move it twice;
    - results are `Done`, `NotYours`, `WrongStep` and `InvalidAmount`;
    - status changes still go through `Orders.changeStatus` with `Actor.staff(driverId)`, so the state machine and `order_event` apply.
  - `CodLedger`:
    - records the collection and sets the payment to PAID;
    - skips transfers and orders already collected.
    - The driver's `deliver` records the amount the driver typed. It is prefilled with the total, and Arabic digits are accepted.
    - A merchant marking a COD order DELIVERED records the order total.
  - `CodSettlement`:
    - `balances()` gives collected, remitted and outstanding per driver and currency;
    - `outstanding()` is used for the dashboard;
    - `statement(driverId)` lists the driver's collections and remittances, newest first;
    - `remit(...)` refuses non-positive amounts, more than the outstanding balance, and staff who are not drivers. It takes an advisory lock per driver, so two remittances cannot both pass the check.
  - `OrderView.codCollected`.
- **Driver pages** (`/driver`, mobile first, for the DRIVER role):
  - **Home:**
    - a cash card ("cash with you") per currency;
    - the driver's open orders, oldest first, with area and total.
  - **Order page:**
    - call, WhatsApp (`wa.me`) and map (Google Maps search on the address) buttons;
    - the address with the landmark, the items, and the amount to collect;
    - start the delivery; delivered with the collected amount (COD) or without it (transfers); refused with a reason.
  - Every action is post/redirect/get, and outcomes are checked against a fixed list.
- **Merchant admin:**
  - **Settlement** (`/admin/settlement`, owners and managers, nav "التحصيل"):
    - drivers with collected, remitted and outstanding amounts;
    - a statement per driver, with a form to record a remittance (amount and note).
  - **Dashboard:** a fourth card, "cash with drivers", for owners and managers.
  - **Order detail:** "cash collected: X (by driver)".

### Tests (494, all green: 261 unit, 233 integration)

| Test | Checks |
|---|---|
| `DriverDeliveryIT` (7) | The done-when above; refusal with the reason in the history and no cash recorded; transfers are delivered without a cash record; steps happen once and in order (a second start or deliver, or a deliver before start, changes nothing), and a different amount is kept and shown to the merchant; drivers see only their own orders (404 for other drivers and unassigned orders; drivers and plain staff get 403 on the settlement); a merchant marking delivered records the cash; another store sees no cash and gets 404 on the statement and on remitting |
| `CheckoutE2EIT` (3) | Adds the driver and settlement flow in Edge at phone size |

### Decisions and deviations

- **Supersedes the M9 note:** a merchant marking a COD order delivered now records a collection for the full total and sets it PAID. The cash then counts against the assigned driver, or against no driver if none was assigned. Otherwise the cash would disappear from the books.
- **The driver may type a different amount** (a customer short of change, or a discount at the door). The ledger keeps what was collected, and the order total is unchanged.
- **Remittances are per currency** and cannot exceed the outstanding balance. Corrections (a negative remittance, editing an entry) are not supported; the ledger is append-only.
- **No live curl check this time.** The Edge test drives both phones through real pages on a real server.

## M11: Notifications (done, 2026-10-15)

**Done when:** order status changes produce notifications in Mailpit and `notification_log`.
- `NotificationIT.orderStatusChangesReachTheShopperAndTheMerchants`, with JobRunr's server running and a Mailpit container as the SMTP server:
  - placing a COD order emails the owner and the manager (not plain staff or a deactivated manager): Arabic subject, items, total, customer, address, payment method and a link to the admin order page;
  - the shopper's text goes to the application log, since no SMS or WhatsApp provider is configured;
  - `notification_log` has one entry per message and recipient, all SENT;
  - confirmed, out for delivery (with the cash to have ready) and delivered each add one shopper message, and preparing adds none;
  - the admin order page lists the messages.
- Live run against docker-compose (local profile):
  - Flyway applied V13 and V14 to the dev database, and the `cart-expiry` recurring job was registered.
  - Confirming demo1 order #1005 in the admin logged the shopper's Arabic text about a second later.
  - Placing order #1007 on the demo1 storefront logged the shopper's text and put the owner's email in Mailpit (http://localhost:8025).
  - The order page lists both messages.
- `./mvnw verify` is green with 500 tests.

### What was built

- **Schema:**
  - `V13__jobrunr_storage` is a Java Flyway migration that runs JobRunr's own table scripts as the schema owner. The app starts JobRunr with `skip-create` as `app_user`, which can use the tables but not alter them. They are platform tables, registered in `RlsCoverageIT`.
  - `V14__notification_log.sql` creates `notification_log`, one row per message and recipient:
    - template, order, channel (EMAIL, SMS, WHATSAPP, or LOG when no provider), recipient, status (PENDING, SENT, FAILED), attempts, provider reference, last error and sent time;
    - `dedupe_key` is unique per tenant;
    - `tenant_id` with RLS.
- **Notification module:**
  - `OrderMessages` listens to `OrderStatusChanged` (placed, confirmed, out for delivery, delivered) and the new `PaymentProofRejected`.
    - Each event becomes a JobRunr job, `SendOrderMessages`, whose id is derived from the event. A redelivered event is skipped by JobRunr ("already exists").
    - If queuing fails, the Modulith event stays incomplete and is republished after a restart.
  - `SendOrderMessagesHandler` runs in the event's store. It renders the shopper's text and, for a new order, an email to each active owner and manager (`identity.StaffContacts`).
    - If a message fails, the job fails and JobRunr retries it with back-off.
  - `Deliveries` sends one message in its own transaction, holding the log row's lock. A message already SENT is skipped, so a retry repeats only what failed.
  - `NotificationTexts`:
    - texts come from the bundles (`notification.*`) in the store's language and digits, since shoppers have no language on record;
    - the merchants' email is `templates/notification/new-order.html` with a plain-text alternative, inline styles and `dir` from the locale.
  - **Channels:**
    - `NotificationChannel` (sealed) has four implementations:
      - `EmailChannel`: SMTP via `spring.mail`, active once `MAIL_HOST` is set, and then `MAIL_FROM` is required;
      - `WhatsAppChannel` and `SmsChannel`: stubs bound to `WHATSAPP_*` and `SMS_*`;
      - `LoggingChannel`: the fallback.
    - `ChannelRouter` picks email for emails, and WhatsApp, then SMS, then the log for phones. It logs its choice at startup.
  - `NotificationLog.forOrder` returns the entries for an order, for the admin.
- **Verification codes:**
  - The customer module now declares `CodeSender`, and the notification module implements it. Before, customer depended on notification, and notification now depends on order, which depends on customer; Modulith would have flagged the cycle.
  - Codes are sent at once (not queued) and logged as `otp` without the code.
- **Order module:** rejecting a payment proof publishes `PaymentProofRejected` with staff's reason. The shopper's message quotes it.
- **Jobs:** `CartExpiryJob` moved from `@Scheduled` to a JobRunr `@Recurring` job (`cart-expiry`, hourly at :17), so it runs once however many instances there are. `SchedulingConfig` and `app.scheduling.enabled` are gone.
- **Merchant admin:** the order page has a "Messages" section showing what was sent, to whom, through which channel, with the status and any error.
- **Configuration:**
  - `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD`, `MAIL_FROM`, `MAIL_SMTP_AUTH` and `MAIL_STARTTLS` (both default true), and `APP_LINK_SCHEME` (default https).
  - The local profile points at Mailpit (`localhost:1025`, no auth or TLS).
  - The JobRunr dashboard is off, and its anonymous usage reporting is disabled.
  - Tests disable the JobRunr server except in `NotificationIT`.

### Tests (500, all green: 261 unit, 239 integration)

| Test | Checks |
|---|---|
| `NotificationIT` (5) | The done-when above; a rejected payment proof tells the shopper the reason; Mailpit refusing recipients (chaos mode) leaves the emails FAILED with the 451 error, and after it accepts again JobRunr's retry sends them while the shopper's text, already sent, is not repeated; an event published twice sends nothing twice; another store sees none of the entries |
| `RlsCoverageIT` | JobRunr's tables listed as platform tables |
| `ApplicationSmokeIT` | Health stays UP without a mail server: the mail health indicator is off, since failed emails are retried rather than making the app unhealthy |

### Decisions and deviations

- **Shoppers have no email** (SPEC 5 `customer`), so their status messages are phone messages. Until an SMS or WhatsApp provider is chosen (SPEC 19), they go to the application log and are recorded with channel LOG. Mailpit shows the merchants' new-order emails.
- **SMS and WhatsApp are stubs.** Configuring one routes phone messages to it, and they fail with "not integrated yet" in the log rather than pretending to send. WhatsApp business-initiated messages need Meta-approved templates, which come with the provider decision.
- **No merchant WhatsApp alert yet:** the store's `whatsapp` number has no settings screen, and there is no provider. It comes with the setup wizard (M13) and a provider.
- **Messages use the store's default language,** since an order does not record the shopper's language.
- **Only `cart-expiry` is a recurring job so far.** The other SPEC 14 jobs (stale reservations, auto-cancel, billing) arrive with the features that need them.

## M12: Billing (done, 2026-10-15)

**Done when:** a suspended store shows a "temporarily unavailable" storefront.
- `BillingIT.unpaidStoreIsSuspendedAndReopensOncePaid`, with the daily run called for explicit days:
  - a store gets a 14-day trial on `basic`;
  - when the trial ends, a $10 invoice for the next month is issued, due that day, and the subscription becomes PAST_DUE with 7 days' grace;
  - the owner sees a "payment due" banner;
  - on the last grace day nothing happens. The next day the store is SUSPENDED (by billing) and its storefront answers 503;
  - the owner still reaches the admin and the billing page;
  - the platform admin records 4 (typed in Arabic digits), then 6 (7 is refused as more than is left), so the invoice is PAID;
  - the store reopens (TRIAL, since it was never screened), and the subscription is ACTIVE with a fresh month from today.
- `NotFoundPageIT.rendersTemporarilyUnavailableForASuspendedStore`: through a real server, the Arabic page "هذا المتجر غير متاح مؤقتاً" with status 503.
- Live run against docker-compose (local profile):
  - V15 applied, and every existing store got a trial at startup; `billing-daily` was registered next to `cart-expiry`.
  - demo1's owner sees the plan, its limits, the trial and the configured pay-to on `/admin/billing`.
  - The platform admin suspended demo2: its storefront answered 503 with the Arabic page.
  - Activating it without the screening checkbox was refused. With it, demo2 was ACTIVE again and its storefront answered 200.
  - The subscriptions, invoices, plans, plan edit and new-invoice pages all render.
- `./mvnw verify` is green with 512 tests.

### What was built

- **Schema (`V15__billing.sql`):**
  - Platform tables (no `tenant_id`, no RLS, as SPEC 5 lists them; registered in `RlsCoverageIT`):
    - `plan` (code, name, monthly price in USD, limits as JSON, active), seeded with `basic` ($10) and `pro` ($25);
    - `subscription`: one per store, with the plan, status, current period and grace date as Syrian calendar days;
    - `invoice`: a platform-wide number from a sequence (shown as INV-1001), amount and currency, OPEN/PAID/VOID, due date, and the billed period. It is unique per store and period, so a month is billed once;
    - `invoice_payment`: WALLET, BANK or CASH, a reference, the amount, and who recorded it.
  - `store.plan_code` is dropped; the subscription holds the plan.
  - `store` gains screening fields (`screened_at`, `screened_by`, `screening_notes`) and `suspension_kind` (BILLING or MANUAL) with a reason.
- **Tenant module:**
  - `StoreAdministration`:
    - `suspend` (by hand, reason required);
    - `activate` (the first activation needs the sanctions/KYC checkbox, recorded with notes and who checked);
    - `suspendForNonPayment` and `resumeAfterPayment` for billing. Billing only lifts its own suspensions: one made by the platform admin stays, even after paying.
    - Store rows are locked during changes.
  - `StoreRegistered` is published when a store is created, and `StoreActivated` when the platform admin activates one.
  - `TenantResolutionFilter`: a SUSPENDED store's storefront answers 503 with `error/503.html` ("temporarily unavailable"); the admin and driver areas keep working.
- **Billing module (new):**
  - `Plans`, `Subscriptions` and `Invoices`:
    - hand-written invoices;
    - partial payments; more than what is left is refused;
    - voiding with a reason;
    - once nothing is owed, a PAST_DUE or SUSPENDED subscription is ACTIVE again and a billing suspension is lifted.
    - Locks are taken in the order invoice, subscription, store.
  - `BillingCycle.run(today)`:
    - starts trials for stores without a subscription;
    - bills every month that has started, one by one, each in its own transaction under the subscription lock. A free plan starts its month without an invoice;
    - suspends PAST_DUE subscriptions whose grace ended with an invoice still open.
    - It is safe to run twice on the same day.
  - `BillingJobs`:
    - the JobRunr recurring job `billing-daily` (03:00 Asia/Damascus);
    - a trial on `StoreRegistered`;
    - on `StoreActivated`, a subscription billing had suspended resumes: PAST_DUE with a fresh month and a new grace period if an invoice is still open, otherwise settled;
    - missing trials at startup (the local demo stores, and stores from before billing).
  - Settings: `BILLING_PAY_TO` (where merchants send money), `BILLING_TRIAL_DAYS` (14) and `BILLING_GRACE_DAYS` (7).
- **Merchant admin:**
  - `/admin/billing` (owners and managers): plan, price and limits, status, trial end or next invoice, how to pay (with the invoice number), and the store's invoices only.
  - A banner on every admin page:
    - "suspended" for everyone;
    - "invoice due by …" and "trial ends on …" (within 7 days) for owners and managers.
  - A Billing nav link.
- **Platform admin** (nav: Stores, Subscriptions, Invoices, Plans):
  - **Stores:** a list with a status filter and subscription. Each store's page shows its status, the suspension reason and kind, and the screening record, with actions to activate (with the screening checkbox and notes), suspend (with a reason) and change the plan (offered plans only, from the next invoice), plus the store's invoices.
  - **Subscriptions:** a list with a status filter.
  - **Invoices:** a list with a status filter and paging. New invoices are written by hand for a store (description, amount in SYP or USD, due date). Each invoice's page lists its payments, with forms to record a payment and to void.
  - **Plans:** a list, and a create or edit form (the code is fixed once created; limits are blank for no limit; offered or not).
- **Shared:** `@dates.day(LocalDate)` for calendar days.

### Tests (512, all green: 261 unit, 251 integration)

| Test | Checks |
|---|---|
| `BillingIT` (11) | The done-when above; activating a billing-suspended store without payment resumes billing with a new grace period, after which it is suspended again; moving to a free plan does not forgive an unpaid invoice; a manual suspension is not lifted by paying, and billing does not take it over; voiding the last open invoice reopens the store, and a void needs a reason; missed months are billed one by one and only once, and a suspended subscription is not billed further; a plan change applies from the next invoice, and a free plan creates no invoice; activation needs the screening once, after which billing reopens the store as ACTIVE; a registered store gets its trial through the event; the platform admin screens list everything, a hand-written invoice is validated and created in SYP, and plan validation and inactive plans work; merchants see only their own invoices, plain staff get 403 on billing and no banner, and staff get 403 on `/platform` |
| `NotFoundPageIT` (+1) | The 503 page through a real server |
| `TenantResolutionIT` | Suspended stores now answer 503 (instead of being served) |
| `RlsCoverageIT` | The billing tables are listed as platform tables |

### Decisions and deviations

- **Billing tables follow SPEC 5 as platform tables without RLS.** Merchants reach them only through the billing module, filtered by the store in their session (`TenantContext`). The JPA repositories run on `app_user`, since these tables have no RLS; `PlatformDatabase` is used only for the platform admin's cross-store store list.
- **Prepaid months:** each month is invoiced on its first day with a 7-day grace. The trial is 14 days, and the default plan is `basic`. SPEC does not fix these, so they are settings.
- **Reopening after payment:** a suspended subscription restarts its month on the payment day, since the store was closed for part of the month it paid for. A store never screened goes back to TRIAL; only the platform admin's activation (with screening) makes it ACTIVE.
- **Hand-written invoices never suspend a store on their own.** Only an unpaid subscription month past its grace does. But while any invoice is open, the subscription does not count as settled.
- **A platform admin's activation does not forgive the debt:** activating a store billing suspended reopens it, and its subscription goes back to PAST_DUE with a new 7-day grace from that day (rather than being suspended again on the next daily run). If the invoice is still unpaid then, billing suspends it again.
- **Review fixes before commit:** a payment locks the subscription before checking for open invoices, so it never settles over an invoice the daily job is issuing at the same moment; a free month after a paid one stays PAST_DUE while an earlier invoice is unpaid.
- **Plan limits are stored and shown but not enforced yet.** Enforcing them (products, staff, orders per month) needs checks in catalog, identity and checkout, and is left for later.
- **No billing emails yet** (invoice issued, store suspended); merchants see the banner in the admin. They can be added as notification templates.

## M13: Setup wizard and polish (done, 2026-10-15)

**Done when:** a new merchant goes live in under 15 minutes in a manual run-through.
- In Edge, `CheckoutE2EIT.newMerchantSignsUpSetsUpAndGoesLive` scripts the run-through:
  - sign up, landing on the wizard with "Go live" disabled, while shoppers get "opening soon" (503);
  - add a delivery zone and a first product through the real forms, and publish it (cash on delivery is on from the start);
  - go live; the storefront then shows the product.
  - It takes about 5 seconds. A person needs the time to type their product, zone and payment details; there are five short forms and no step outside the wizard.
- `StoreSetupIT` (5) covers the same path in MockMvc, and checks the preview, the store details, the SEO tags, `robots.txt` and the sitemap.
- Live run against docker-compose:
  - V16 applied, and the existing stores stayed open;
  - demo1's home and product pages carry the description, Open Graph and canonical tags;
  - `robots.txt` and `sitemap.xml` (Arabic slugs percent-encoded) are served;
  - a new signup lands on `/admin/setup` with "Go live" disabled; its storefront shows "opening soon", and the preview link from the wizard shows the store with the preview banner.
- `./mvnw verify` is green with 521 tests.

### What was built

- **Going live (`V16__store_launch.sql`):**
  - `store.launched_at` and `store.preview_token`. Existing stores are marked launched. Every store gets a random preview token: the migration backfills existing stores with 64 hex characters, and new stores get 32 random bytes in URL-safe Base64 (43 characters).
  - `StoreSummary.launched`, `StoreSettings.launch()` and `previewToken()`, and `StoreLookup.previewAllowed()` (constant-time comparison).
  - `TenantResolutionFilter`: a store not launched answers 503 with `error/503.html` in its "opening soon" form, unless the request carries the preview:
    - `?preview=<token>` on a GET sets an HttpOnly cookie for the rest of the visit;
    - preview pages get `X-Robots-Tag: noindex`, a noindex meta tag, no canonical link, and a preview banner.
  - The local demo stores are seeded as launched, and test stores (`TestStores.create`) are created launched.
- **Setup wizard** (`/admin/setup`, owners and managers; signup now lands here):
  - five steps, each linking to the page that does it:
    - store details (optional);
    - logo and colours (optional);
    - payment methods (required; cash on delivery counts, and is on by default);
    - a delivery zone (required, at least one active);
    - a first product (required, one shoppers can see);
  - the checklist (`SetupChecklist`) reads what the store already has, so steps tick themselves;
  - "Go live" is disabled until the required steps are done, and the server checks again (`?not-ready`);
  - the private preview link;
  - once live, the page says so and links to the store.
  - Until launch, a "Setup" nav link and a dashboard card ("2 of 5 steps done") point back to the wizard.
- **Store details** (`/admin/settings/store`, a new first settings tab):
  - Arabic and English names (at least one), contact phone and WhatsApp, validated and stored in E.164. This was deferred in M11.
  - The storefront footer shows a call link and a `wa.me` link.
- **SEO** (SPEC 9.5):
  - home, category and product pages get a description, `og:type`, `og:title`, `og:description`, `og:image` (the product's first photo, a category's first product, the store logo) and `twitter:card`;
  - descriptions are cut to 160 characters at a word boundary;
  - the merchant's own text is used where there is some (product description, hero text), and a bundle text otherwise;
  - every store page gets `og:site_name`, `og:locale`, `og:url` and `<link rel=canonical>`. The canonical link is the URL without the query (no sorting or tracking parameters), keeping only `?page=`.
- **Crawlers** (`CrawlerController`):
  - `robots.txt` on every host:
    - stores disallow cart, checkout, orders, search and the language switch, and name their sitemap;
    - the main site disallows `/admin/` and `/driver/`;
    - the platform admin disallows everything (its security chain lets `/robots.txt` through).
  - `sitemap.xml` per store lists the home, visible categories and visible products (with `lastmod`), from `StorefrontCatalog.sitemap()` with the storefront's visibility rules. It is 404 on the main site.
  - Both are cached publicly for an hour.
- **Error pages:**
  - `error/4xx.html` and `error/5xx.html` for statuses without their own page (400, 405, 500…), in the storefront layout, noindex, with a link home and no detail;
  - the 404 and 403 pages are noindex too;
  - 503 now distinguishes "opening soon" from "temporarily unavailable".
- **Empty states:** the lists already had them (M3–M12). The empty product list now has an "Add your first product" button.

### Tests (521, all green: 261 unit, 260 integration)

| Test | Checks |
|---|---|
| `StoreSetupIT` (5) | The wizard flow in MockMvc: closed storefront, disabled launch, `?not-ready`, ticks after adding a zone and product, launch, live storefront, setup card and nav gone; the preview link (a wrong token, the cookie, `noindex`, no canonical, the banner, product pages through the cookie, another store's token refused); store details validation and the footer links; description, Open Graph and canonical tags on home, product (tracking parameter dropped) and category (page kept, sort dropped), and none on search; `robots.txt` on store, main and platform hosts, the sitemap with categories and products but not drafts, and 404 on the main host |
| `ErrorPagesIT` (7, was `NotFoundPageIT`) | Adds: "opening soon" for an unlaunched store; a 500 from a failing page shows the Arabic page without the exception or its message; a 400 shows the client-error page; 404, 400 and 500 pages carry `noindex` |
| `CheckoutE2EIT` (+1) | The run-through above in Edge |
| `MerchantSignupIT` | Signup now redirects to `/admin/setup` |

### Decisions and deviations

- **"Go live" is a launch date, not a store status.** TRIAL and ACTIVE stay about billing and screening (M12), and launching is the merchant's own decision. A store that is suspended later still shows "temporarily unavailable" first.
- **Required steps:** a payment method, a delivery zone and a visible product, because without them a shopper cannot order. Store details and logo/colours are optional, and marked so.
- **Stores from before M13 count as launched,** so nothing that is open today closes.
- **The preview is a link, not a login.** Admin sessions live on the main host and do not reach store subdomains. The token is only shown in the admin and only works before launch. Previewed pages are never indexed.
- **No hreflang:** the language comes from a cookie, not the URL, so there is one address per page. Canonical URLs use the request's scheme and host, so production needs proxy headers trusted (M15).
- **No JSON-LD product markup:** SPEC 9.1 allows no inline scripts. The Open Graph tags cover link previews.
- **Done after M15:** the "exchange rate is weeks old" dashboard hint noted in M4 (see "Stale exchange rate hint" below).

## M14: API v1 skeleton and OpenAPI (done, 2026-10-16)

**Done when:** the SPEC 13 endpoints return data, and the docs are at `/api/docs`.
- `ApiIT` (11) covers the endpoints, the security chain and the docs.
- Live against docker-compose on demo1:
  - `GET /api/v1/stores/demo1` returns the store;
  - with its id in `X-Store-Id`, `GET /api/v1/products` lists the catalog ($ prices converted to ل.س as on the storefront, image renditions from MinIO), and `GET /api/v1/products/{id}` returns a product;
  - without the header, a 400 problem detail and no cookie;
  - `/api/docs` opens Swagger UI.
- `./mvnw verify` is green.

### What was built

- **`api` module** (SPEC 3), depending on catalog, inventory, media, pricing and tenant through their public types only.
- **Security chain** (`ApiSecurityConfig`, ordered before the web chains): `/api/**` on the main host.
  - Stateless: no session, no cookies, CSRF off.
  - Bearer tokens are HS256 JWTs signed with `JWT_SECRET` (at least 32 bytes), and must carry `exp`. Without a secret the API refuses every token, and the catalog reads still work. The local and test profiles have their own keys.
  - The three catalog reads and the docs are open; everything else under `/api/**` needs a token.
  - Authentication errors are 401 with `WWW-Authenticate: Bearer` and a problem detail.
  - Store hosts answer `/api/**` with 404; the platform host sends it to its login, like any other path there.
- **Tenant** (`ApiTenantFilter`, SPEC 4.1): a merchant token's `store_id` claim, or the `X-Store-Id` header.
  - The header reaches only stores open to shoppers: unknown, closed or unlaunched stores are 404, suspended ones 503.
  - A token's store only has to exist (not closed), so merchants can use the API before launch.
  - A header naming another store than the token is 403; a header that is not a canonical UUID is 400.
- **Endpoints** (`StoreApiController`, `ProductApiController`):
  - `GET /api/v1/stores/{slug}`: id, slug, name, default language, display currency, colour, logo, contact numbers, storefront URL. Same availability rules as the header.
  - `GET /api/v1/products`: 24 per page; `sort` (`newest`, `price_asc`, `price_desc`), `category` (a slug, with subcategories) or `q` (search by relevance), `page`. Invalid values are 400, not silently replaced as on the storefront.
  - `GET /api/v1/products/{id}`: available variants with `inStock`, options with only the offered values, images, category.
- **Responses:** both languages for every text; prices in the display currency, converted and rounded like the storefront, as strings with two decimals; images as 320/640/1024 rendition URLs. Errors are RFC 9457 problem details (`ApiExceptionHandler`, scoped to the API; problem details stay off for pages), never with exception text.
- **Catalog:** `StorefrontCatalog.products(sort, scale, page)` and `product(UUID)`, sharing the queries and visibility rule of the category and slug lookups. `PriceScale.forDisplay` moved the price-sort scaling out of `StorefrontController`, so both use it.
- **OpenAPI** (springdoc 2.9.1, the line built on Boot 3.5): only the API package is described. Document at `/api/docs/openapi.json`; `/api/docs` redirects to Swagger UI at `/api/swagger-ui/index.html`. The `X-Store-Id` header and the bearer scheme are documented.

### Tests (535, all green: 264 unit, 271 integration)

| Test | Checks |
|---|---|
| `ApiIT` (11) | Store by slug, no cookie; unknown, unlaunched, suspended and closed stores. Products: newest first, drafts absent, $4.50 → 535.00 SYP, `fromPrice`, price sort, category, Arabic search, an empty later page. A product with options, variants, compare-at price and stock; draft, unknown and malformed ids. Isolation: another store's product by id, in the list and in search is never shown with this store's header. Problem details for a missing or malformed header, unknown store, bad sort, page (negative, huge, non-numeric), q with category, unknown category. Tokens: the claim selects the store, works before launch, and a different header is 403; expired, forged, endless and malformed tokens are 401. Other API paths need a token. The API is not served on store hosts. The OpenAPI document lists the three paths and the header but no pages; the docs redirect, and the UI and its configuration load |
| `PriceScaleTest` (3) | Dollars scaled up for SYP display, pounds down for USD display, no rate → raw amounts |

### Decisions and deviations

- **No token endpoint.** SPEC 13 limits the MVP to the chain and three reads; issuing tokens (merchant login, refresh) comes with the mobile app. Until then nothing issues tokens, and the chain accepts tokens signed with the shared secret. Rotating to asymmetric keys (RS256 with a JWKS) is worth considering when a separate service issues them.
- **API errors are English,** fixed texts for developers. They are not user-facing pages, so they are not in the message bundles; the app shows its own messages.
- **Amounts are strings with two decimals** (`Money`'s scale), so no client parses them as floating point, and the format never changes with the value.
- **Strict parameters:** unlike storefront links, the API rejects unknown sorts and pages with a 400, so client bugs show up.
- **No caching headers or rate limits yet:** rate limits come with M15 (production readiness). Responses vary by the `X-Store-Id` header, so a shared cache would need `Vary: X-Store-Id`.
- **Unknown paths under `/api/v1` with a valid token** get Boot's JSON 404 body rather than a problem detail, since no API controller handles them.

## M15: Production readiness (done, 2026-10-16)

**Done when:** the image builds and runs against a fresh database. Done live:
- `./mvnw package` and `docker build` produced `ecommerce:m15`.
- A throwaway compose project ran it with the `prod` profile against a new PostgreSQL 16 (roles from `01-roles.sh`, with non-default names and passwords) and a new MinIO bucket:
  - Flyway applied V1–V16; the app created the bucket and the first platform admin from the environment, and seeded no demo data. Logs were JSON from the first line.
  - Plain http got a 308 to https; health answered over http.
  - Through proxy headers: HSTS, Secure session cookie. Signup, the wizard, a product with a photo (WebP renditions encoded in the container, served from the bucket), a delivery zone, launch, the storefront with https canonical links, the API, platform admin login, and a COD order. The order's notification was logged as JSON with the store's `tenantId`.
  - As the runtime role without a tenant, `product` returned no rows; with one, its rows; creating a table was refused.
- `ProductionProfileIT` repeats the proxy, https and rate-limit parts on a real server in every build.
- `./mvnw verify` is green.

### What was built

- **Image** (`Dockerfile`, `.dockerignore`): packages the jar Maven built, in Boot's layers (dependencies apart from the application), on `eclipse-temurin:21-jre-noble`.
  - Glibc for the WebP natives, so not Alpine.
  - Runs as uid 10001, `prod` profile, UTC, `MaxRAMPercentage=75`, exits on out-of-memory.
  - Building needs no network beyond the base image. Maven inside a build stage would also have failed on this machine's TLS interception.
  - CI builds the image on pushes to `main` (no registry yet).
- **`prod` profile** (`application-prod.yml`):
  - **Forwarded headers:** `server.forward-headers-strategy: native`, so Tomcat's RemoteIpValve takes the client address and https from proxies on private or loopback addresses. This feeds the rate limits, Secure cookies, canonical links, redirects and HSTS. A client cannot get around it by prepending addresses to `X-Forwarded-For`.
  - **`app.https-only`:** `HttpsRedirectFilter` (first filter) answers plain http with 308 to https, except `/actuator/`. Spring Security already sends HSTS on https responses.
  - **JSON logs:** Boot's ECS format on stdout.
- **Tenant in logs:** `TenantContext` puts `tenantId` in the logging MDC and restores it with the scope, so request and job log lines say which store they are about.
- **Sentry** (`sentry-spring-boot-starter-jakarta` and `sentry-logback` 8.59.0): off without `SENTRY_DSN`.
  - Its exception resolver runs last, so only exceptions nothing else handled (real 500s) become events, plus ERROR log lines.
  - `send-default-pii: false`.
- **Rate limits** (`shared.RateLimits`): per client address, in memory, Bucket4j and Caffeine, `Retry-After` on 429.
  - Storefront (`StorefrontRateLimitFilter`, before security): verification texts 10/hour, code checks 30/15 min, orders 20/hour, payment proofs 20/hour, cart changes 120/min. Browsers get a localized `error/429.html`; HTMX gets the page's error banner.
  - API (`ApiRateLimitFilter`, in the API chain before token checks): 300/min, as a problem detail.
  - Login and signup limits (M2) are unchanged.
  - Off in the test profile, since all MockMvc requests share one address and the context is shared across classes.
- **springdoc:** docs enabled explicitly (public on purpose), which silences its production warning.
- **`docs/operations.md`:**
  - what runs where;
  - setting up a fresh database (roles, the `BYPASSRLS` caveat on managed services);
  - every environment variable;
  - the proxy and HTTPS setup and what breaks without it;
  - logs, Sentry, the rate limits, scaling;
  - backups and restore.
  The README links it.
- **Backups, tested:**
  - `pg_dump` as `app_owner` fails, because the tables force RLS on their owner.
  - Dumping as `app_platform` (BYPASSRLS) works.
  - Restoring as the superuser into a new database gave the same row counts, all 23 tables still forcing RLS, `app_owner` ownership and the runtime grants.

### Tests (542, all green: 265 unit, 277 integration)

| Test | Checks |
|---|---|
| `ProductionProfileIT` (6) | `test` + `prod` profiles on a real server, requests over sockets as a proxy sends them: plain http → 308 to https (GET keeps the query, POST stays POST); readiness over plain http; through the proxy: HSTS with subdomains, Secure CSRF cookie, https canonical link; verification texts limited per forwarded address (429 page in Arabic, `Retry-After`, noindex; another address unaffected; browsing unaffected); a client prepending a made-up address is still counted as the proxy saw it; the API limited per address (problem detail, `Retry-After`) |
| `TenantContextTest` (+1) | The tenant is in the logging context inside a scope, the outer one after a nested scope, and gone after |

### Decisions and deviations

- **The Dockerfile packages a prebuilt jar** instead of building inside Docker: the tests run once (in `verify`), CI caches Maven, and building the image works on machines with TLS interception.
- **One instance.** Sessions and rate limits are in memory. A second instance needs Spring Session JDBC (or sticky sessions) and shared limits (`docs/operations.md`, Scaling).
- **The app does the https redirect**, so a misconfigured proxy is visible at once (redirect loop) instead of silently serving http. Actuator stays on http for probes.
- **No CDN page caching:** pages vary by the language cookie (M5). The guide says what a CDN may cache.
- **Not done:**
  - pushing the image to a registry (needs the hosting choice, SPEC 19);
  - Prometheus stays unexposed;
  - a real Sentry project was not connected, so event delivery is untested; the configuration loads and stays off without a DSN.
- **Fixed after M15:** the SMS stub logged phone numbers and message texts (M11), which JSON logs would ship to a log service. See "Masked notification logs" below.

## After M15: Masked notification logs (2026-10-16)

The M15 known issue: without an SMS, WhatsApp or mail provider, `LoggingChannel` wrote each message in full to the log, including the phone number or address, order details and verification codes. In production those logs go to a log service.

- **Masked by default:** `app.notification.log-content` (default `false`). The log gets one WARN line per message: medium, masked recipient (`+963******666`, `o***@demo1.test`), "not delivered", and the text's length, never the text.
- **Full content in `local` and `test` only:** developers read verification codes from the console (README), and `CheckoutWebIT`, `PhoneVerificationIT` and `NotificationIT` read them the same way.
- **Unchanged:** the merchant still sees every message on the order page (`notification_log`); that table is protected by row-level security like the rest of the store's data.
- **WARN rather than INFO:** in production a message that is not sent means a shopper got nothing, which operators should see.

| Test | Checks |
|---|---|
| `LoggingChannelTest` (4) | Masked: no number, code or store name in the log for a text, no address, subject or body for an email; full content when enabled; short or malformed recipients fully masked |

## After M15: Stale exchange rate hint (2026-10-16)

Open since M4: shoppers pay for dollar-priced products at the store's rate, and nothing reminded merchants when it fell behind the market.

- **Rule (pricing):** `ExchangeRates.STALE_AFTER` is 7 days. `ExchangeRate.staleAt(now)` and `ageInDays(now)` live with the rate, not in the dashboard.
- **Dashboard:** owners and managers (who set the rate) get an amber banner next to the existing "no rate yet" one: "حددت سعر صرف الدولار (1 دولار = 118.5 ل.س) منذ 12 يوماً …", with a link to the pricing settings. Staff do not see it.
  - The Arabic plural follows the number: 10 أيام, 12 يوماً, 100 يوم.
  - The "1" is a formatted argument, so stores with Arabic-Indic digits get it in their digits (the M4 lesson).
- **Every store with a rate is reminded,** even one whose products are all in the display currency. That matches the "no rate yet" nudge. Showing it only to stores with products in the other currency would need a catalog query for one banner.
- **Live check (demo1):** with its 2-day-old rate there was no banner. With the rate backdated 10 days, after the 60 s rate cache expired, the banner appeared in Arabic ("منذ 10 أيام") and English ("was set 10 days ago"). The date was restored afterwards.

| Test | Checks |
|---|---|
| `ExchangeRateAgeTest` (3) | Fresh for exactly 7 days, stale a second later; whole days counted |
| `PricingIT` (+1) | No banner at 6 days; at 12 days the Arabic banner with the rate and "منذ 12 يوماً", not the "no rate" one; staff don't see it; setting a new rate removes it |

## After M15: Staff passwords (2026-10-16)

Deferred since M2: once added, a staff member's details and password could not change, and anyone who forgot a password had no way back in.

### What was built

- **Editing staff** (`/admin/staff/{id}`, owners and managers):
  - name, phone, email and role, with the same contact checks as adding (normalized, at least one, not another member's);
  - the actor must manage both the old and the new role, and never edits themself. Managers get 404 for owners, other managers and themselves, and 403 when promoting to a role they do not manage;
  - a new role ends the member's sessions (the session filter already re-checked the role).
- **Setting a password for someone** (same page): for a member who forgot theirs.
- **Own password** (`/admin/account`, every admin role; the name in the header links there): current password, new one twice. This session stays logged in (logged in again with the new principal); others end.
- **Forgotten password** (`/admin/password/forgot` and `/reset`, open without login; "Forgot your password?" on the admin login):
  - store plus the email or phone the member logs in with; the answer is the same whether or not the account exists;
  - a link goes to whichever was typed: email, or a text once an SMS provider exists (the log until then; masked outside local runs);
  - links work once, for 30 minutes (`StaffPasswords.LINK_LIFETIME`), for active members of an open store;
  - only the token's SHA-256 is stored (`password_reset`), and the notification log keeps the template and recipient, not the text;
  - the reset page sends `Referrer-Policy: no-referrer` and `Cache-Control: no-store`;
  - a reset clears the member's login lock, since they proved they own the email or phone;
  - limited to 10 requests per address and 3 per account per hour (`LoginThrottle`); beyond that nothing is sent.
- **Every password change** (`PasswordChanges`): the new hash, `staff_user.credentials_version` + 1, and the member's open reset links closed. `StaffPrincipal` carries the version it logged in with, and `StaffSessionFilter` ends sessions whose version is behind, on the next request.
- **Delivery:** `identity.ResetLinkSender`, implemented by the notification module like `customer.CodeSender`, so identity does not depend on notifications. Email reset messages are plain text: `EmailChannel` now sends text-only emails.
- **Migration** `V17__staff_passwords.sql`: `credentials_version`, and `password_reset` with RLS, keyed to the staff member by `(tenant_id, staff_id)`.
- **Also:** the warning logged when a message fails to send now masks the recipient too, like the log channel.

### Tests

| Test | Checks |
|---|---|
| `StaffPasswordsIT` (12) | Owner edits name, phone and role; the new role ends the member's session and the new phone logs in. Another member's email refused, one's own kept. Managers: owners, themselves and promotions refused; staff can't open the page. A password set by the owner: too short refused; old session ends, old password fails, new works. Own change: wrong current, mismatch, then success keeping this session and ending the other. Forgotten password by email: login locked by failures, form prefilled with the store, same answer, link from the log, `no-referrer` header, mismatch, reset, login works, the old session ends, the link works once. By phone (text). Unknown account or store: same answer, nothing sent. Disabled member: nothing sent. Expired link and another store's slug refused, old password still works. Fourth request in an hour sends nothing. Only the hash in `password_reset`; the notification log has template and recipient |

**Live check (local profile):**
- "Forgot your password?" for demo1's owner put an email in Mailpit: Arabic subject and text, plain text only, the link valid for 30 minutes.
- The link opened the form (`no-referrer`, `no-store`) and set a new password, and the owner logged in with it. Opening the link again said it no longer works.
- The owner's staff list had Edit links, and the edit page showed both sections.
- On `/admin/account` the owner changed the password back to the README's `demo-pass-123` and stayed logged in. A fresh login with it worked.

### Decisions

- **Drivers** have no account page or reset link; their manager sets a new password (the driver view is phone-first and minimal).
- **Owners don't edit their own details** on the staff page (no one manages an owner). Their name and contacts stay as signed up for now; only the password can change.
- **Requests answer at once, after sending.** A real account takes as long as sending the email, so timing could hint that an account exists. The per-address and per-account limits make probing slow; sending in the background would close it.
- **No email verification:** a member's email is whatever the owner typed. A reset goes only to an address already on the account, never one typed in the form.

## After M15: Sign-in shop sign (2026-10-18)

The login, forgot-password and reset panels open with a shop sign: a rolled-up shutter over the address of the store being logged in to (`demo1.lvh.me`). It confirms which store the member is logging in to, which the "store address" field alone did not make clear.

### What was built

- **`fragments/auth.html :: sign`**: the shutter and the address. The member's part of the address is bold, the platform's domain lighter. It reads left to right but lines up with the page's start edge, and long slugs wrap. An empty slug shows as a dashed blank. The sign repeats the store field, so it is `aria-hidden`.
- **States:** `opening` on the login (the shutter rolls up once, 450 ms; no animation with `prefers-reduced-motion`), `open` on forgot and reset, `closed` when a reset link no longer works (shutter down, no address). The driver login shares the template and gets the sign too.
- **Live address:** the Alpine component `storeSign` (`app.js`) follows the `#store` input as it is typed. Without that input it keeps the address the server rendered.
- **Styles** in `tailwind/app.css` (`.shop-sign*`), plus `--color-brand-800` for the slats. System fonts only; no new JS library.
- **`PasswordResetController`:** the store domain is a model attribute on every response, and the store typed on the forgot form reaches the "sent" page as a flash attribute, so its URL stays `?sent`.

### Tests (563, all green: 272 unit, 291 integration)

| Test | Checks |
|---|---|
| `StaffPasswordsIT` (+1, now 13) | Login and forgot pages show the store on a live sign, the login's shutter opens; sending a link keeps the store (flash); a working reset link shows the store on an open sign; an unusable one shows a closed sign with no address |

**Live check (local profile):** the login, forgot, reset and driver login pages rendered the sign; after "Send link" the sign kept `demo1`; an expired link showed the closed shutter; owner login still worked. Headless Edge screenshots in Arabic and English, desktop and ~500 px wide, with a long slug. Edge will not render narrower than about 492 px, so a 375 px phone view and the roll-up animation itself were not seen.

## After M15: Merchant landing page (2026-10-18)

The main site's `/` showed a "coming soon" placeholder. It now shows a landing page for merchants deciding whether to open a store, leading to signup.

### What was built

- **Hero:** the shop sign from the sign-in pages, full width. Its address (`____.lvh.me`) follows the field under it as it is typed; "Open your store" opens `/signup?slug=<address>`. The shutter rolls up once on load (not with `prefers-reduced-motion`). Below it, the trial length from `billing.trial-days`.
- **An order's path** in four numbered steps (a real sequence): the customer orders and sees the delivery fee, the merchant confirms (and checks transfer receipts), their driver delivers, the cash collected is tracked until handed over.
- **Three facts** as rows: the payment methods (from `PaymentMethod`), pricing in pounds or dollars at the merchant's rate, a delivery fee per area.
- **Prices:** the active plans (`Plans.all()`), as columns of a table: monthly price, products, staff, orders a month ("No limit" for empty limits). Platform admin edits show up here.
- **Routing:** the storefront still owns `/`; without a store (the main site) it forwards to `/welcome`, served by `merchantadmin`'s `LandingController`. The storefront chain denies `/welcome` on store hosts (404).
- **`Plans.trialDays()`** (new), so the page states the configured trial.
- **Signup prefill:** `GET /signup?slug=` fills the address, normalized (lowercase, trimmed), even if invalid, so the visitor corrects it rather than retyping; submitting reports problems as before.
- **Removed:** `storefront/placeholder.html` and its `placeholder.*` keys.
- The sign component (`storeSign`) reads its input from `data-input` (default `#store`); `.shop-sign--hero` styles in `app.css`.

### Tests (568, all green: 272 unit, 296 integration)

| Test | Checks |
|---|---|
| `LandingPageIT` (5) | `/` on the main site forwards to `/welcome`; the page has the live sign, the GET form to `/signup`, the seeded plans and limits, payment methods, no missing keys; English with "A 14-day free trial"; a store host answers 404 for `/welcome` and its `/` is the storefront; signup prefills ` My-Shop ` as `my-shop` |
| `MainSiteLanguageIT` (3, was `PlaceholderPageIT`) | Arabic RTL by default with hashed asset URLs, English LTR remembered in a cookie, unsupported languages ignored, now on `/welcome` |
| `TenantResolutionIT` (changed) | The base domain, `www.` and other hosts forward `/` to the landing page |

**Live check (local profile):** headless Edge screenshots in Arabic and English, 1280 px and 500 px wide; the Arabic trial line reads "14 يوماً". Typing into the field was not exercised in a browser (same component as the login sign).

### Decisions

- **No testimonials, numbers or screenshots:** none exist yet, and the page does not invent them.
- **Product name and domain** still come from `app.name` and the base host (SPEC 19 open question); the page changes with them.

## After M15: Signup and setup shopfront (2026-10-18)

Signup and the setup wizard continue the shop sign from the sign-in and landing pages.

### What was built

- **Signup:** the panel opens with the store's sign. The store name typed in the form shows on it large, the address under it (`storeSign` now also follows a name input, `data-name-input`). Until there is a name, the address keeps the display size. Arriving from the landing page, the chosen address is already on it.
- **Setup:** a shopfront at the top: the store's sign (name and address, always readable) over a doorway whose shutter is down by the share of steps still to do (`Checklist.percentToDo()`, inline height). A fresh store is at 60%: details (the signup phone) and payments (cash on delivery) are done. On `?launched` the shutter rolls up once (not with `prefers-reduced-motion`). The shopfront is `aria-hidden`; "2 of 5 steps done" stays in text.
  - A first version lowered the shutter over the sign itself; at partial heights it cut through the store name. Sign above, shutter over the doorway below, as on a real shopfront, keeps the name whole.
- **Steps** as the landing page's numbered sequence instead of bordered cards: a filled slot with ✓ when done, a solid outline when required, dashed when optional; going live is the last step (6).
- **Layout fix (all four layouts):** a page's own `class` on `<main layout:fragment="content">` replaced the layout's, dropping the width cap and padding, so 22 pages (setup, staff, the storefront home, ...) touched the screen edges on phones. Width and padding now live on a wrapper `<div>` around `<main>` in `admin`, `driver`, `platform` and `storefront`.

### Tests (568, all green: 272 unit, 296 integration)

| Test | Checks |
|---|---|
| `StoreSetupIT` (changed) | Fresh store: shutter at 60%; after a zone and a product, 20%; after going live, the shopfront opens (`shop-front--opening`) with no inline height |
| `LandingPageIT` (changed) | The signup sign follows the name and address fields and starts from the chosen address |

**Live check (local profile):** headless Edge screenshots of signup (filled in, with an error), a fresh store's setup (60%) and demo1's live setup, at desktop and ~500 px width; demo1's storefront home at ~500 px after the layout fix. Logged-in pages were saved with curl (with a `<base>` to the app) and screenshotted, since headless Edge cannot log in. The roll-up and typing into signup were not exercised in a browser; driver and platform pages were not looked at after the layout fix. A test store `ward2649` was signed up in the local database.

## After M15: Merchant admin shell and dashboard ledger (2026-10-18)

The admin header listed up to 13 links in a row; on a phone it wrapped to three lines. The dashboard was three big-number cards.

### What was built

- **Sidebar (lg and up)** on the start side: the store's sign at the top (name, address, plan status, under a slim rolled-up shutter; `.shop-sign--compact`), the navigation, then the account (name linking to `/admin/account`, log out, language).
- **Phone bar (below lg):** the store's name and a "Menu" `<details>` (no script) holding the same navigation and account.
- **Navigation** (`fragments/admin-nav.html`, shared by both) in four groups separated by rules: the day's work (home, orders, customers), the catalog (products, categories, inventory), money and delivery (cash collection, delivery; owners and managers), running the store (settings, staff, billing; owners and managers). "Set up your store" leads, filled, until the store is live. The current page has `aria-current="page"` and a start-edge bar (`adminSection` from `AdminModelAdvice`: the first path segment after `/admin`).
- **Badge:** "Orders" (and "Menu" on phones) shows how many orders await confirmation, with "N awaiting confirmation" for screen readers. `AdminModelAdvice.ordersToConfirm` counts on full admin GET pages only, not for HTMX fragments, form posts or the driver area.
- **Dashboard ledger:** "Today" as rows of label, dotted leader and figure: awaiting confirmation, payments to check, orders today, cash held by drivers (owners and managers). Non-zero rows that need action get an amber start edge; zeros are grey; each row links to its list. The store's address moved to the sign, so `DashboardController` no longer needs `Sites`.
- **Messages:** `admin.nav.menu`, `admin.nav.label`, `admin.nav.orders.to-confirm`, `dashboard.today` added; `dashboard.store-address` and `dashboard.orders` removed.
- Public pages using the admin layout (login, signup, password reset) keep the plain header.

### Tests (569, all green: 272 unit, 297 integration)

| Test | Checks |
|---|---|
| `OrderManagementIT` (+1, now 11) | With two orders awaiting confirmation: the badge on a non-dashboard page for staff, the current page marked, managers' links hidden from staff; the dashboard marks home as current, shows managers' links to the owner and the amber ledger rows; HTMX requests carry no count |

**Live check (local profile):** demo1 (five orders awaiting confirmation), pages saved with curl and screenshotted in headless Edge: dashboard and orders at 1280 px, dashboard at 900 and 500 px, the phone menu forced open. The first ledger had `border-amber-400` colouring every side of a row, which hid the dividers; it now colours the start edge only (`border-s-amber-400`). Opening the menu by tapping was not exercised. On phones the store's address and status are not shown (they are on the desktop sign only).

## After M15: Storefront sign and price tags (2026-10-18)

The storefront is the merchant's shop, so the sign from the platform's pages becomes the store's own, in its colour.

### What was built

- **Header as the store's sign** (`layouts/storefront.html`): a full-width band in the store's colour (`brand-700`; `ThemeStyle` keeps white text readable on any merchant colour) with the logo on a white tile, the store's name, call and WhatsApp links (moved from the footer) and a white cart button. Search, language and categories sit on a white strip under it.
  - Pages can replace the name (`sign-name`) and add a tagline (`sign-tagline`). Pages without a store (main-site error pages, a store opening soon) get a plain white header instead of a coloured band.
- **Home:** the store's name on the sign is the page's `h1`, set larger. The merchant's headline and text, when set, are the sign's tagline. The default "Welcome to {store}" is gone (it repeated the name right above it); `shop.home.welcome` removed from both bundles. Categories on the home page are pills instead of bordered tiles.
- **Price tags:** prices on product cards and the product page sit on a small tag in the store's colour (`brand-700` on `brand-50`). Product names on cards are clamped to two lines.
- **Opening soon** (`error/503.html`): the store's sign with its shutter fully down, as the merchant sees it on the setup page. `TenantResolutionFilter` now sets `storeOpeningSoon` to the store's name instead of `true`.
- CSS: 7.4 KB gzipped (budget 30 KB, SPEC 9.5). No JavaScript added.

### Tests (569, all green: 272 unit, 297 integration)

| Test | Checks |
|---|---|
| `StorefrontIT` (changed) | The home `h1` inside the header is the store's name (Arabic and English); a merchant's headline is the tagline under it |
| `TenantResolutionIT` (changed) | A store's home greets with its name as the `h1`, in both languages |
| `ErrorPagesIT` (changed) | Opening soon shows the store's name on a sign with the shutter at 100% |

**Live check (local profile):** headless Edge screenshots of demo1's home (1280 and 500 px) and a product page, demo2 with a burgundy theme injected into a saved copy, and `ward2649`'s opening-soon page. No demo store has a phone, WhatsApp, logo or headline set, so those parts of the sign were not seen with data. Cart and checkout inherit the header but were not redesigned or looked at.

## After M15: Driver delivery slips (2026-10-18)

The driver view is used on the road, one-handed, often in sunlight. Orders now read as delivery slips, and the address leads with the landmark, which Syrian addresses rely on.

### What was built

- **Home** (`driver/home.html`): the cash the driver holds at the top, in an amber panel with a large figure. Each order is a slip: number and status, the area and city large (`driver.place`, "{0}، {1}" / "{0}, {1}"), the landmark, the customer's name, then a tear line (`.slip__tear`: dashed rule with a notch cut into each edge) and what to collect, or "paid by transfer". The order out for delivery has a green border and a filled status chip.
- **Order** (`driver/order.html`): the customer's name as the heading; call (filled), WhatsApp and map as large buttons; the address card leads with the landmark, then the governorate › city › area path, details, phone and the customer's note in its own box; the items and the amount to collect form a slip with the same tear line.
  - The next step ("I picked it up", or the collected amount and "Delivered and cash collected") sits in a bar stuck to the bottom of the screen, within reach of the thumb. "Customer refused" stays behind a disclosure.
- Driver copy is unchanged; type and borders are larger and heavier for reading outdoors.

### Tests (569, all green: 272 unit, 297 integration)

| Test | Checks |
|---|---|
| `DriverDeliveryIT` (changed) | The landmark leads the address; "I picked it up" sits in the bottom bar |
| `CheckoutE2EIT` (changed) | The driver opens the order by its link (number and name are no longer one line) |

**Live check (local profile):** demo1's driver had no orders, so order #1009 was placed on demo1 (cash on delivery, Mazzeh, landmark and a note), confirmed, assigned to أبو خالد, set to preparing and picked up by the driver. It stays in the demo data, out for delivery. Headless Edge screenshots of the driver's home and the order before and after pickup, at ~500 px. The status chip for an order out for delivery reads "في الطريق إليك", the shopper's wording; a driver-specific label is a possible follow-up.

## After M15: Checkout slip and order progress (2026-10-18)

Shoppers now see the same delivery slip as the driver: at checkout as a live preview, and on their order page.

### What was built

- **Checkout** (`storefront/checkout.html`): the order summary is a delivery slip. Above the tear line, the address as the driver will read it, live from the form (`slipPreview` in `app.js`): the name, the landmark large, then governorate › city › area, captioned "Your driver will read your address like this". While the landmark is empty, the slip asks for one (`checkout.slip.landmark-missing`). Below the tear line, the summary as before (its own border removed). The slip stays in view on large screens. Without JavaScript the preview stays hidden (`x-cloak`) and the page works as before.
- **Order page** (`storefront/order.html`):
  - A numbered track of the order's steps (awaiting confirmation, confirmed, preparing, out for delivery, delivered; "completed" once closed), done ones ticked, the current one filled with `aria-current="step"`. Cancelled, refused and returned orders show their status alone. Payment status is a line under it.
  - The address and the items form one slip with a tear line, landmark first, as on the driver's phone.
- Messages: `checkout.slip.caption`, `checkout.slip.landmark-missing`, `order.track`.

### Tests (569, all green: 272 unit, 297 integration)

| Test | Checks |
|---|---|
| `CheckoutWebIT` (changed) | The checkout page carries the slip preview; a new order's first step is the current one |
| `CheckoutE2EIT` (unchanged) | Caught that a completed order's track ended on "delivered", so the shopper never saw "completed"; the last step now reads "completed" once the order is closed |

**Live check (local profile):** headless Edge screenshots of order #1009's page (out for delivery, 1280 and 500 px) and of a saved copy of demo1's checkout with the address fields filled in, where the preview filled from them. The summary's refresh on an address change was not watched in a browser.

**Noticed, not changed:** in Arabic, `shop.cart.subtotal` and `checkout.total` are both "المجموع", so the summary shows the same label twice.

## After M15: Platform overview ledger and store status marks (2026-10-18)

The platform admin's dashboard listed the 50 newest stores. It is now the operator's ledger of what needs following up.

### What was built

- **Overview** (`/platform`, `PlatformController`): "What needs you" as ledger rows (label, dotted leader, figure), each linking to its filtered list: invoices awaiting payment (from the first page of open invoices, "50+" beyond it), stores past due, trials ending within 7 days, suspended stores, new stores in the last 7 days. Non-zero rows of the first three get an amber edge; zeros are grey. Same pattern as the merchant dashboard. Below, the 10 newest stores, linked to their detail pages, and "All stores (N)". The page is now titled "Overview" (`platform.dashboard.title`).
- **Store status mark** (`platformadmin/fragments.html :: store-status`, `.store-mark`): next to a store's status on the overview, the stores list and the store page, a tiny shopfront: a sign over a lit doorway while the store trades, a grey shutter down when it is suspended or closed. The label carries the meaning; the mark is `aria-hidden`.
- **Navigation** (`layouts/platform.html`): an "Overview" link, and the current section underlined with `aria-current="page"` (`PlatformModelAdvice.platformSection`).
- Messages: `platform.dashboard.recent`, `platform.ledger.*`; `platform.dashboard.title` and `store-count` reworded.

### Tests (569, all green: 272 unit, 297 integration)

| Test | Checks |
|---|---|
| `BillingIT` (changed) | After a billing cycle, the overview shows the open invoice and past-due rows with an amber edge and links to the filtered lists; the overview and stores pages mark their own navigation link; the stores list carries the status mark |

**Live check (local profile):** headless Edge screenshots of the overview and stores list as `admin@lvh.me`. Locally nothing is owed, past due or suspended, so the amber rows were checked only by the test, and the closed mark only in a saved copy with one row edited to show it.

## After M15: Merchant order page, next step and delivery slip (2026-10-18)

The order page staff use all day now leads with the order's progress and puts what each decision needs beside its buttons.

### What was built

- **Track:** the shopper's numbered track of the order's steps moved into a shared fragment (`fragments/order.html :: track`), used by the shopper's and the merchant's order pages.
- **Next step** (the status block, its heading and buttons unchanged): an amber start edge while the order waits on the merchant (to be confirmed, or a transfer proof to check), green otherwise. While it awaits confirmation, the customer's name, their phone as a large call link and their record ("delivered N · refused N", amber when they have refused before) sit next to the buttons. Driver assignment stays in the panel.
- **Delivery slip:** the address (landmark first, the area path, details, the customer's note in an amber box), a tear line, then the items and totals: the slip the driver carries.
- **Customer card:** name, phone, record (amber with refusals), block and unblock; titled "Customer" now that the address is on the slip (`admin.orders.customer`).

### Tests (569, all green: 272 unit, 297 integration)

| Test | Checks |
|---|---|
| `OrderManagementIT` (changed) | A new order can be confirmed but has no "delivered" action (checked on the form: the step name now shows on the track); the next-step panel has its amber edge; the track marks the current step |

**Live check (local profile):** headless Edge screenshots, as demo1's owner, of order #1008 (awaiting confirmation, 1280 px) and #1009 (out for delivery, 500 px). No demo customer has a refusal, so the amber record was not seen with data.

## After M15: Merchant orders list, status tabs (2026-10-18)

The orders list filtered by a status dropdown. It now opens on the order's pipeline, so staff see at a glance where orders are waiting.

### What was built

- **Status tabs** (`merchantadmin/orders/list.html`): "In progress" (all open orders), then the four open steps in order (awaiting confirmation, confirmed, preparing, out for delivery), each with its count; the awaiting-confirmation count is amber when above zero. A divider, then "All" and the closed statuses (no counts). The tabs replace the status dropdown and are part of the results fragment, so every search or filter change refreshes them. The current tab is marked `aria-current="page"`.
- **Filters:** the view rides along with the search and payment filter in a hidden field tied to the form (`form="order-filters"`). Tab links carry only the filters that are set (no `&payment=&q=` in the address bar).
- **Rows:** orders awaiting confirmation or a payment check get an amber start edge, as on the dashboard. On phones each order is a stacked block (number and status; customer and total; date, payment method and status) instead of a sideways-scrolling table; the desktop table keeps its columns.
- **Counts:** `Orders.countsByStatus()`, every status with zero for none, from one grouped query (`ShopOrderRepository.countGroupedByStatus`), limited to the current store like every order query.

### Tests (569, all green: 272 unit, 297 integration)

| Test | Checks |
|---|---|
| `OrderManagementIT` (changed) | With two orders awaiting confirmation, "In progress" is the current tab, the awaiting-confirmation tab shows 2 in amber, and the hidden field carries the view; an HTMX request for one status returns a fragment that keeps the view and marks its tab |

**Live check (local profile):** headless Edge screenshots of demo1's orders list as the owner, at 1280 px and 500 px.

## After M15: Catalog admin, the product on its shelf (2026-10-18)

The product page opened on a row of status buttons. It now opens on the product as the shop shows it, beside what publishing it needs.

### What was built

- **Shelf** (`product-edit.html :: shelf`, `ShelfPreview`): the product's card (main photo, name, the shop's price tag with "from" when variants on sale differ in price, the old price struck through) standing on a shelf board (`.shelf__board`); a dashed frame without a photo, "No price" in amber without a price. Beside it, the status in plain words (with a link to the product in the shop once published) and three needs, each linking to its section: a price on a variant on sale (required: amber with a hint when missing, and Publish is disabled until there is one), a photo and a description (optional: an empty ring and a hint). The status actions moved into the panel. Its edge is amber while a draft, brand while published, grey once archived.
- **Live:** the photo and prices sections, swapped over HTMX, also send the shelf out of band (`ProductController.refreshShelf`), so it follows an upload or a price change.
- **Product list:** status tabs (all products, published, draft, archived), each counted (`Products.countsByStatus`, one grouped query), in place of the status dropdown; the status rides along with the search in a hidden field tied to the form, and tab links keep the search. Prices are the shop's price tags, "No price" in amber; phones get stacked rows; a product without a photo shows a dashed frame.
- Messages: `product.shelf.*`, `product.no-price`, `product.tab.all`; `product.status.all` removed.

### Tests (574, all green: 276 unit, 298 integration)

| Test | Checks |
|---|---|
| `ShelfPreviewTest` (new) | The lowest price on sale with its old price; "from" only when prices differ; not sellable without a price above zero on sale; photo and description |
| `CatalogIT` (changed) | Uploading a photo and saving prices send the shelf out of band; a published product's shelf links to the shop; a product without a price shows the amber needs and a disabled Publish; new `listsProductsInStatusTabsWithCounts`: tab counts, a status tab with a search, the hidden field and the tab links |

**Live check (local profile):** headless Edge screenshots as demo1's owner: the list at 1280 and 520 px, a draft without photo or price (1280 px), the published shirt (1280 px), and a draft with a photo and an old price (520 px), created for the check and deleted after.

**Noticed, not changed:** the list says "from" whenever a product has several variants, even at one price; the shelf uses the admin's colour, not the store's theme colour; an empty status tab says "No products match your search" without a search.

## After M15: Product page, error pages, settings, categories and inventory (2026-10-18)

Four design passes in one go: the storefront product page, the error pages, the merchant settings, and the rest of the catalog admin.

### What was built

- **Storefront product page** (`storefront/product.html`, app.js `gallery`, `variantPicker`): photos in a strip that swipes one at a time, with thumbnails that bring a photo into view and mark the current one (the strip still scrolls without JavaScript); the price on a swing tag in the store's colour (`.swing-tag`, pointed and punched on the start side, mirrored for RTL); option choices that cannot be bought with the others crossed out (`buyable`); a − / + quantity stepper; under the button, where the store delivers (the fee shows in the cart) and how to pay, from `DeliveryZones.coverage()` and `PaymentMethods.enabled()`, hidden when empty; the description under "About this product".
- **Error pages** (`fragments/error.html`, `error/*.html`): a sign hung on a nail by two strings, as on a shop door ("Not here", "No entry", "Back in a moment", "Not understood", "Out of order"), the code beneath; then the heading, the explanation and a button home (404 and 403 had none). The 503 shopfront is unchanged.
- **Merchant settings:**
  - **Theme** (`settings/theme.html`, app.js `themePreview`): a live copy of the shop beside the form (sign with name and logo, home title and text, two price tags, the add-to-cart button) following the colour, title, text and font as they are typed; shades computed as `ThemeStyle` does.
  - **Tabs:** the settings tabs match the order and product tabs, mark the current page and scroll on phones.
  - **Payments:** cards have a brand edge when enabled, grey when off.
  - **Delivery zones and staff:** lists that stack on phones; zone fees as price tags; paused zones and disabled members greyed.
- **Categories** (`catalog/categories.html`): a tree, subcategories hanging off a line under their parent, hidden ones greyed; a visible category's address links to its page in the shop; product counts in words (`category.product-count`, Arabic plurals).
- **Inventory** (`inventory.html`): a list that stacks on phones; a tracked variant with nothing available shows an amber field and "Out of stock".
- Messages: `shop.product.*` (photo, delivery, payment, about), `shop.list.separator`, `shop.cart.quantity.less/more`, `error.*.sign`, `settings.theme.preview*`, `category.product-count`, `inventory.sold-out`; table header keys (`zone.col.*`, `staff.col.*`, `category.col.*`, `inventory.col.product/variant/sku`) removed.

### Tests (575, all green: 276 unit, 299 integration)

| Test | Checks |
|---|---|
| `StorefrontIT` (changed) | The product page's swing tag and quantity stepper, no delivery block without zones; new `productPageSaysWhereTheStoreDeliversAndHowToPay`; the theme page's preview and current tab |
| `ErrorPagesIT` (changed) | The door sign's words and code on 404, 500 and 400, and the button home |
| `CheckoutWebIT` (changed) | Enabled payment methods have the brand edge, others grey |
| `CatalogIT` (changed) | The category tree: the shop link, the hidden child on its line with its product counted |
| `InventoryAdminIT` (changed) | A variant set to 0 shows "Out of stock" in amber |

**Live check (local profile):** headless Edge screenshots on demo1 at 1280 and ~500 px: the product page (with a second photo added to the shirt for the gallery, removed after), a 404, the theme page with a colour and title typed in, payments, zones, staff, categories and inventory.

**Noticed, not changed:** a 404 on a real store shows the platform header, not the store's sign (the error dispatch does not know the store), so it cannot offer the store's search; the theme preview shows the title in the admin's language only.

## After M15: Review fixes (2026-10-21)

Fixes for a code review of the previous pass.

- **Product page delivery line:** a governorate the store covers only in part names its cities, and the areas of a city covered only in part, e.g. "Latakia (Latakia: Al-Zira'a, Jableh)" (`shop.product.delivery.areas`).
- **Categories:** a visible subcategory under a hidden parent is not in the shop, so it is greyed, has no shop link and says "Hidden with its parent" (`inShop`, `category.status.parent-hidden`).
- **Theme preview:** one implementation of the colour shades, `tenant.ThemePalette`, used by `ThemeStyle` and by the preview through `GET /admin/settings/theme/palette?color=` (app.js no longer copies the maths). Font stacks moved to `FontChoice.stack()`. The preview is coloured from the server before any script runs, in the admin's language. A refused colour puts the saved colour back in the colour input instead of black.
- **Product page cost:** delivery coverage and enabled payment methods are cached per store (`shared.TenantCache`, 60 s, evicted on change and again after commit).
- **Arabic plurals:** `@plurals.of(n)` (`PluralText`) gives the CLDR category (zero, one, two, few, many, other), so 100 منتج, 105 منتجات, 250 منتجاً; `category.product-count.<category>`.
- **Customers table** has its own `admin.customers.col.actions`; `category.col.actions` is gone.

### Tests

| Test | Checks |
|---|---|
| `ThemePaletteTest` (new), `ThemeStyleTest` (changed) | Shades only for `#rrggbb`; readable button shade; 500 is the colour |
| `PluralTextTest` (new) | Arabic categories by the last two digits; English one/other |
| `StorefrontIT` (changed) | Partly covered governorate names its places; coverage and payment changes show on the next view; refused colour keeps the saved one; palette endpoint; English preview title |
| `CatalogIT` (changed) | Subcategory under a hidden parent: no link, "Hidden with its parent" |

**Live check (local profile):** on demo1, the palette endpoint (404 for a non-colour), a refused colour keeping the saved one, category counts in Arabic, and the product page's delivery line ("دمشق (دمشق)، ريف دمشق، حلب (حلب)": demo1's zones cover Damascus city, not the whole governorate).

## After M15: Settlement (2026-10-21)

A design pass on cash-on-delivery settlement.

- **List** (`settlement/list.html`): a ledger like the dashboard's, drivers holding cash first with an amber edge and the amount held; under each, per currency, a cash track (`.cash-track`): the bar is what they collected, green for what they handed over, hatched amber for what they still hold, with "Handed over X of Y".
- **Driver** (`settlement/statement.html`): what the driver still holds in the amber box the driver sees on their own page, with collected and handed over as ledger lines; the handover form as a receipt torn off above "Received by {you}"; the statement: collections and handovers newest first, each with what the driver held after it (`SettlementViews.lines`, worked back from the current balance; when a list is full, older lines are left out and the page says so). Rows stack on phones.
- Messages: `settlement.settled`, `settlement.track`, `settlement.holds*`, `settlement.received-by`, `settlement.statement*`; `settlement.collections` and `settlement.remittances` removed.

### Tests

| Test | Checks |
|---|---|
| `SettlementViewsTest` (new) | Drivers holding cash first; percent handed over; held-after per line; lines older than a full list left out |
| `DriverDeliveryIT` (changed) | The amber "still with", the statement order with held-after amounts, the cash track's percent |

**Live check (local profile):** on demo1, driver أبو خالد delivered orders 1005 and 1009 (cash 2,535 and 2,570) and the owner recorded 3,000 handed over ("Evening handover"); these stay in the demo data. Headless Edge screenshots of the list (1280 px) and the driver page (1280 and 500 px). Signs on statement amounts (+/−) were dropped after the screenshots: the money format is RTL-shaped and the sign landed on the wrong side.

**Accessibility pass:** list rows get an inset focus ring (the rounded list clips an outside one); a zero balance is gray-500, not gray-400 (2.5:1); the amount field is `aria-invalid` after a refused handover. Not checked below ~492 px (headless Edge clamps the window).

**Noticed, not changed:** the statement shows the latest 50 collections and 50 handovers; there is no paging to older lines.

## After M15: Password reset hardening and whole photos (2026-10-21)

Part 1 of the plan agreed on 2026-10-21 (then themes, merchant reports, platform reports).

### What changed

- **Reset requests reveal nothing by timing:** after the rate limit, everything (finding the store and account, making the link, sending it) runs on another thread (`PasswordMessages.inBackground`), so the answer takes as long for an unknown account as for a real one (measured locally: 18 to 26 ms either way; before, a real account waited for the link to be made and sent). Messages go after the transaction commits, through an executor rather than application events, which are stored (a link must not be).
- **Only the newest link works:** asking again closes every link still open for the member (`PasswordChanges.closeLinks`).
- **Password changed notice:** every change (own, set by an owner or manager, or by reset) sends the member an email, or a text when they have no email: their password changed, they were signed out elsewhere, and what to do if it was not them (`ResetLinkSender.passwordChanged`, template `password_changed`, `notification.password_changed*`).
- **Whole photos:** product photos in storefront cards, product thumbnails, the cart, the admin product list and product page, and payment proof screenshots are shown whole (`object-contain` on a light square) instead of cropped to fill it.

### Tests

| Test | Checks |
|---|---|
| `StaffPasswordsIT` (changed) | Links are read once sent (messages arrive after the response); new `onlyTheNewestLinkWorks`, `tellsMembersWhenTheirPasswordChanges` (and nothing for a refused change) |
| `WholePhotoTemplatesTest` (new) | No `<img>` in a template uses `object-cover` |

**Live check (local profile):** reset requests for real and unknown demo1 accounts timed alike; links and the "password changed" email arrived in Mailpit; one open link per account after repeated requests; staff@demo1.test reset back to its demo password. The storefront home at 1280 px (demo photos are square, so the change shows on non-square photos only).

## After M15: Storefront looks and dark admin screens (2026-10-21)

Part 2 of the plan agreed on 2026-10-21.

### Storefront looks

The merchant picks a look on the storefront settings page (a small drawing of each, its name and the kind of shop it suits); every look keeps the store's colour, logo and font. Stored as `look` in the theme JSON (`StoreTheme.Look`, null is Souq), so no migration. The storefront layout sets `data-look`; app.css restyles the header and product grid per look, and templates change only where the structure does.

- **Souq** (default): unchanged; the store's sign in its colour, price tags, two to four columns.
- **Boutique**: a white header with a rule in the store's colour, categories as quiet text links, three columns of tall (4:5) photos, prices as plain text.
- **Grocer**: a compact sign, products as rows (photo, name, the price large) with an "Add" button that puts a single-variant product straight into the cart (HTMX, `ProductCard.onlyVariantId`), or "Choose" for products with options.
- **Showroom**: a near-black header with a rule in the store's colour, the newest product that has a photo featured large (picked in `StorefrontController.home`, not repeated in the grid), categories as tiles, products as bordered cards with 4:3 photos.

The settings preview follows the look being picked.

### Dark admin screens

Each person chooses Device (the default), Light or Dark with a switch at the bottom of the admin sidebar, in the driver header and in the platform admin header; the choice is stored on their account (`V18__color_scheme.sql`: `staff_user.color_scheme`, `platform_user.color_scheme`; `identity.ColorSchemes`). Layouts write `data-scheme`; a tiny inline script in the head sets `data-dark` before the page paints, following the device as it changes. One dark stylesheet in app.css turns the palette rather than adding `dark:` to every template: the grey ramp runs the other way, light tints become dark ones, white surfaces become dark ones, and dark text shades of each colour become light ones; buttons keep their colour. The storefront preview and the look drawings stay light. Login pages and the storefront have no scheme. Switching goes back to the page it was on, same area only.

### Tests

| Test | Checks |
|---|---|
| `StorefrontIT` (changed) | New `laysTheStoreOutByItsLook`: Souq grid, Grocer rows with quick add and Choose, Showroom tiles and the featured photo (only with a photo, not repeated), Boutique links; the admin saves a look and the preview and picker show it |
| `ColorSchemeIT` (new) | Dark is remembered across sessions; the switch goes back only to a page of the same area; unknown values change nothing; drivers and platform admins switch too; storefront and login pages carry no scheme |

**Live check (local profile):** demo1 in each look at 1280 px and Grocer at 500 px; the admin dashboard, an order, the storefront settings and a driver's statement in dark; the platform store list in dark. Fixed after the screenshots: a featured product without a photo left an empty panel (now only products with photos are featured), the look drawings turned dark, the platform header's email was too faint. demo1's theme and both demo accounts' schemes were set back afterwards.

## After M15: Merchant reports (2026-10-21)

Part 3 of the plan agreed on 2026-10-21. Owners and managers get **Reports** in the admin navigation (money group), with four reports as tabs:

- **Sales:** per currency the period's sales as the headline, against as many days just before ("12% more than the 30 days before"), orders and the average order as ledger lines; sales by day (up to a month), week (up to a quarter) or month as a column chart, the same figures as a table underneath. Sales are orders placed in the period except cancelled, refused and returned ones, in the currency the shopper paid (`total_display`); currencies are never added together.
- **Products:** best sellers by units (with the deleted ones under the name on the order), products on sale that sold nothing, tracked variants with 5 or fewer available (out of stock in amber).
- **Orders and delivery:** orders placed and the share lost, every status as a bar (lost ones amber), time to deliver (median as "usually", and average), delivery by zone, per driver (delivered, refused, time, cash collected), lost orders with the reason the merchant or driver wrote.
- **Customers and payments:** new against returning buyers, top customers (linked to the customers page), payments by method, and what waits now: payment proofs to review and cash with drivers.

Every report has the period picker (today, 7 days, 30 days, this month, last month, or custom dates up to a year, never past today; Damascus days), a CSV per table (`/admin/reports/<report>.csv?table=...`: UTF-8 with a byte order mark for Excel, plain numbers, the currency in its own column, typed text starting with = + - @ prefixed with an apostrophe so it never runs as a formula), and a Print button (the admin chrome and the controls hide on paper; bars print in colour). Charts are plain HTML and CSS: one series in the brand colour, 2px gaps, rounded tops, a grid at the top value (1, 2 or 5 times a power of ten) and its half, a tooltip on hover or keyboard focus, labels spaced back from the newest column; they work in dark mode.

**Where the queries live:** a new read-only `report` module (`StoreReports`, `ReportPeriod`) queries the order, catalog, inventory, delivery and staff tables with SQL inside the store's tenant context, so row-level security applies; it writes nothing and adds no tables. Going through each module's API would have needed a new query per module for every figure.

### Tests

| Test | Checks |
|---|---|
| `ReportPeriodTest` (new) | Presets, the 30-day fallback, custom ranges kept within today and a year, the period before, day/week/month groups without gaps, Damascus day boundaries |
| `ReportFormatsTest` (new) | Chart top rounding, one chart per currency, about eight labels; CSV byte order mark, quoting, formula-looking text kept as text |
| `ReportsIT` (new) | Over real orders (two customers, a cancelled order with its reason, a driver delivery with cash): every figure of the four reports; the pages, the period in links, CSV download and an unknown table; staff get 403 and no nav link; another store sees nothing |

**Live check (local profile):** the four reports on demo1 for the last 30 days at 1280 px, and a CSV download. Fixed after the screenshots: the last two chart labels collided, and a Latin customer name next to an Arabic date ran together in the lost orders list.

**Noticed, not changed:** one demo1 order's cancellation reason was saved as "??????" by an earlier shell session; the data, not the report.

## After M15: Platform reports (2026-10-22)

Part 4, the last of the plan agreed on 2026-10-21. The platform admin gets **Reports** in its navigation with four reports, the same period picker, CSV per table and print view as the store reports:

- **Revenue:** monthly recurring revenue (paying subscriptions, active or past due, at their plan's monthly price) by plan with trials beside it; invoices issued in the period by status and currency; what is overdue now (amber); payments received over the period as a column chart per currency.
- **Stores:** every store by status now (with the store mark); new stores over the period as a chart of counts; where the period's new stores are now (paying, still trying, stopped); subscriptions stopped in the period (by their last change, since status history is not kept); trials ending in the next 14 days.
- **Activity (totals only):** sales and orders across every store, how many stores sold, the busiest stores, trading stores without an order for 30 days. No customer name, phone or address appears.
- **Operations:** messages per channel (sent, failed, waiting) with the latest failures and their error, never the recipient; background jobs by state with the latest failed ones; events no listener finished within an hour; image storage per store from the bucket (`ImageStore.usageByStore`, listed on demand; "could not be read" when storage is down).

**Where the queries live:** platform reports read across stores, so they use `PlatformDatabase` (the RLS-bypassing role) and live in the platform admin module (`platformadmin.internal.web.reports`), which `TenancyArchitectureTests` allows; the store reports stay on the tenant-bound database. The chart model, the CSV writer and the report page fragment are shared: `report.Chart` (amounts per currency or plain counts), `report.Csv`, `fragments/report.html` (with `reportBase` and `reportKeys` for the two areas).

### Tests

| Test | Checks |
|---|---|
| `ChartAndCsvTest` (moved and extended from `ReportFormatsTest`) | Charts of counts (no currency, a half of 2.5 for a top of 5), labels anchored at the newest column |
| `PlatformReportsIT` (new) | Over a shared database (each check finds its own store or compares before and after): an invoice and a payment move revenue; the new store's trial ends in 14 days; a store 40 days old without orders is quiet; a failed text shows its error and not its recipient; storage counts an uploaded image; login is required; pages and a CSV, unknown table 404 |

**Live check (local profile):** the four platform reports for the last 30 days at 1280 px. Fixed after the screenshots: the signups chart labelled its half-way line "3" for a top of 5, and storage under 1 MB read "0.0 MB" (now KB).

**Fixed on the way (part 1):** reset requests made in quick succession for one member created their links on parallel background threads, which raced to close the same older link; one lost on the version check and its email was never sent (seen once as a failure of `limitsResetRequestsPerAccount` in a full run). Making a link now locks the member's row first (`StaffUserRepository.lockById`), so requests for one member take turns; the test also checks that no background work failed and that exactly one link stays open.
