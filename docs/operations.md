# Operations

How to run the platform in production (SPEC 15, 18): the image, what it needs around it, its configuration, and
backups. Local development is in the README.

## What runs where

| Piece | Production choice | Notes |
|---|---|---|
| App | The Docker image (`Dockerfile`), one instance | Stateless except for sessions and rate limits, which are in memory (see Scaling) |
| Database | Managed PostgreSQL 16 with point-in-time recovery | Three roles, see below |
| Images | S3-compatible bucket (S3, Cloudflare R2) | `public/` readable by everyone, the rest private |
| Email | Any SMTP service | Optional; without it emails only go to the log |
| Reverse proxy | Load balancer or proxy that terminates TLS | Wildcard DNS and certificate for `*.<domain>` and `<domain>` |
| Errors | Sentry | Optional |

Hosts: stores answer on `<slug>.<domain>`, the main site (signup, merchant admin, driver view, API) on `<domain>`,
the platform admin on `admin.<domain>`. All three need DNS and TLS.

## Image

```bash
./mvnw verify                      # or: ./mvnw -DskipTests package
docker build -t ecommerce .
```

The image packages the jar from `target/`; building it needs no network but the base image (Eclipse Temurin 21
JRE on Ubuntu, because the WebP encoder needs glibc). It runs as uid 10001, with the `prod` profile, in UTC, on port
8080. CI builds it on every push to `main`.

Probes, over plain http (the https redirect lets `/actuator/` through):

- liveness: `GET /actuator/health/liveness`
- readiness: `GET /actuator/health/readiness` (includes the database)

Shutdown is graceful: running requests finish (up to 30 seconds) before the JVM exits.

## A fresh database

The app expects three roles (SPEC 4.3) before it starts; Flyway then creates the schema on the first start.

- `app_owner`: owns the database and schema, runs Flyway. NOBYPASSRLS.
- `app_user`: runtime connections. NOBYPASSRLS, owns nothing.
- `app_platform`: platform admin, billing and backups. BYPASSRLS, owns nothing.

`docker/postgres/init/01-roles.sh` creates them and the database. Run it once, as a role allowed to create roles,
with the passwords you will give the app:

```bash
export PGHOST=... PGUSER=postgres POSTGRES_USER=postgres
export DB_NAME=ecommerce DB_OWNER_PASSWORD=... DB_APP_PASSWORD=... DB_PLATFORM_PASSWORD=...
./docker/postgres/init/01-roles.sh
```

On a managed service, check that its admin role may create a role with `BYPASSRLS`; some services reserve it.
Without it the platform admin and billing see no store data.

On first start with an empty `platform_user` table, the app creates the first platform admin from
`PLATFORM_ADMIN_EMAIL` and `PLATFORM_ADMIN_PASSWORD`. Remove both variables once you have logged in.

## Configuration

All from environment variables. Required unless a default is shown.

| Variable | Purpose |
|---|---|
| `APP_BASE_DOMAIN` | e.g. `example.com` (no port in production) |
| `DB_URL` | `jdbc:postgresql://host:5432/ecommerce`, with `?sslmode=require` on a managed service |
| `DB_OWNER_USER`, `DB_OWNER_PASSWORD` | Flyway |
| `DB_APP_USER`, `DB_APP_PASSWORD` | Runtime |
| `DB_PLATFORM_USER`, `DB_PLATFORM_PASSWORD` | Platform admin and billing |
| `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | Media bucket |
| `S3_PUBLIC_BASE_URL` | Where browsers load public images, e.g. a CDN in front of the bucket |
| `S3_ENDPOINT` | Empty for AWS S3; the endpoint for R2 or MinIO |
| `S3_REGION` | Default `us-east-1` |
| `S3_PRESIGN_ENDPOINT` | Host in signed links to private images, if browsers reach storage differently than the app |
| `S3_MANAGE_BUCKET` | Default `false`: create the bucket and its `public/*` read policy yourself |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASSWORD`, `MAIL_FROM` | SMTP; empty host: emails go to the log |
| `JWT_SECRET` | API bearer tokens: 32+ random bytes (`openssl rand -base64 48`); empty: tokens refused |
| `SENTRY_DSN`, `SENTRY_ENVIRONMENT`, `SENTRY_RELEASE` | Error reporting; empty DSN: off |
| `BILLING_PAY_TO`, `BILLING_TRIAL_DAYS`, `BILLING_GRACE_DAYS` | SaaS billing (defaults 14 and 7 days) |
| `SMS_PROVIDER`, `SMS_API_KEY`, `WHATSAPP_TOKEN`, `WHATSAPP_PHONE_ID` | Not wired to a provider yet: texts go to the log |
| `PLATFORM_ADMIN_EMAIL`, `PLATFORM_ADMIN_PASSWORD` | First start only, see above |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | Only if the proxy is not on a private address, see below |

`.env.example` lists the same variables with local values.

## Reverse proxy and HTTPS

The app expects a proxy in front that terminates TLS and sends `X-Forwarded-For` and `X-Forwarded-Proto`.
With the `prod` profile it trusts these headers from private and loopback addresses only (Tomcat's default). If
the proxy reaches the app from a public address (for example a CDN calling straight in), set
`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to a regular expression matching its addresses. Otherwise:

- every shopper shares the proxy's address, so the rate limits trip for everyone at once;
- requests look like plain http, so they are redirected to https in a loop.

The app redirects plain http to https itself (308, keeping the method), and sends HSTS (one year, subdomains
included) on https responses. Once HTTPS works for every subdomain, consider HSTS preloading at the proxy.

Pages are cached by browsers only (`Cache-Control: private`), because the language comes from a cookie. A CDN in
front can cache the hashed CSS and JS (`/css/`, `/js/`, a year) and images, but should pass pages through.

## Logs, errors, metrics

- Logs are JSON lines on stdout (Elastic Common Schema). Lines written for a store's request or job carry
  `tenantId`. Collect stdout with whatever the host offers.
- Shopper texts are written to the log until an SMS provider is connected, including phone numbers. Keep log
  retention short, and restrict who can read logs, until then.
- Sentry gets unhandled exceptions (real 500s) and ERROR log lines; not 404s or validation errors, not request
  bodies, cookies or user data.
- Actuator exposes only `health` and `info`. Prometheus metrics are available but not exposed until the endpoint
  can be protected.

## Rate limits

In memory, per instance, keyed by client address (so they depend on the proxy setup above):

| What | Limit |
|---|---|
| Login attempts | 5 per account per 15 minutes; 30 per address per 5 minutes |
| Signups | 5 per address per hour |
| Checkout verification texts | 10 per address per hour; 3 per phone per 15 minutes |
| Code checks | 30 per address per 15 minutes; 5 per code |
| Orders | 20 per address per hour |
| Payment proof uploads | 20 per address per hour |
| Cart changes | 120 per address per minute |
| REST API | 300 per address per minute |

Browsing is not limited by the app; leave flood protection to the proxy or CDN.

## Scaling

Run one instance for now. A second instance would need:

- shared sessions (Spring Session JDBC) or sticky sessions, since logins live in memory;
- rate limits in a shared store, or they multiply by the number of instances;
- nothing for jobs: JobRunr coordinates through the database.

## Backups and restore

**Database.** Rely on the provider's point-in-time recovery (keep at least 7 days) for mistakes and outages. For a
copy outside the provider, take a daily logical dump and keep it encrypted elsewhere.

Dump as `app_platform`. It is the only application role that bypasses row-level security: a dump as the owner
fails, because the tables force RLS on their owner too.

```bash
PGPASSWORD=... pg_dump -h <host> -U app_platform -d ecommerce -Fc -f ecommerce-$(date +%F).dump
```

Restore into a new database as a superuser (or a role with BYPASSRLS that may create objects owned by
`app_owner`). The roles must exist first (`01-roles.sh` on a fresh server):

```bash
psql -U postgres -c "CREATE DATABASE ecommerce OWNER app_owner TEMPLATE template0"
pg_restore -h <host> -U postgres -d ecommerce --exit-on-error ecommerce-2026-10-16.dump
```

This was tested on 2026-10-16 against the local database: the restored copy had the same rows, every table still
forcing RLS, `app_owner` as owner and the runtime grants. Point the app at the restored database; Flyway finds the
schema up to date. Rehearse a restore every few months.

**Images.** Turn on bucket versioning (or the provider's equivalent), so overwritten or deleted objects can be
recovered for a while. Keys are random and never reused, so a restored database only needs the objects it
references; images discarded later are deleted by the app.

**Secrets.** Keep the environment variables in the host's secret store. Rotating `JWT_SECRET` logs out every API
token holder; rotating a database password needs the new value in the app's environment and a restart.
