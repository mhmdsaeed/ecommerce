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
