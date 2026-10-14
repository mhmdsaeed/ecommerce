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

### Next: M1, tenancy core

`store` table, `TenantContext`, subdomain `TenantResolutionFilter`, Hibernate `@TenantId`, `TenantAwareDataSource`, platform DataSource on `app_platform`, RLS on a sample table, `TenantIsolationIT`, `RlsCoverageIT`.
