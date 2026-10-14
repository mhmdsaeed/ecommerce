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

### Next: M5, storefront browse

Home, category, product, search, locale switch, theme settings; Lighthouse mobile performance ≥ 90.
