# ecommerce

Multi-tenant e-commerce SaaS for small merchants in Syria: Spring Boot modular monolith, Thymeleaf + HTMX, PostgreSQL with row-level security.

- Specification: [SPEC.md](SPEC.md)
- Working rules and commands: [CLAUDE.md](CLAUDE.md)
- Milestone progress: [docs/progress.md](docs/progress.md)

## Quick start

Requirements: Java 21 and Docker. No Node.js. Compose publishes PostgreSQL on port 5433 (set `DB_PORT` and `DB_URL` to change it).

```bash
cp .env.example .env               # local-only defaults
docker compose up -d               # PostgreSQL, MinIO, Mailpit
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

In IntelliJ IDEA, use the shared run configuration **EcommerceApplication (local)** (`.run/`), which activates the `local` profile. Without that profile the app has no database URL and stops with `'url' must start with "jdbc"`. Set the project SDK to Java 21.

Open http://lvh.me:8080 (Arabic, RTL) or http://lvh.me:8080/?lang=en. `lvh.me` and its subdomains resolve to 127.0.0.1, so each site gets its own host. Health: http://lvh.me:8080/actuator/health.

The `local` profile seeds demo data. These credentials exist only in local databases:

| Where | URL | Login |
|---|---|---|
| Storefronts | http://demo1.lvh.me:8080, http://demo2.lvh.me:8080 | none |
| Merchant admin | http://lvh.me:8080/admin | store `demo1`, `owner@demo1.test` (or staff `staff@demo1.test`), password `demo-pass-123` |
| Driver view | http://lvh.me:8080/driver | store `demo1`, phone `0933000002`, password `demo-pass-123` |
| Platform admin | http://admin.lvh.me:8080 | `admin@lvh.me`, password `local-admin-pass` |
| New store | http://lvh.me:8080/signup | |
| MinIO console | http://localhost:9101 | `minioadmin` / `minioadmin` |
| Mailpit (sent emails) | http://localhost:8025 | none |

Notifications go out through JobRunr a few seconds after each order event. Emails (the owners' and managers'
new-order alert) land in Mailpit. Shopper texts and verification codes are written to the application log
(`Message to +963…`), since no SMS or WhatsApp provider is integrated yet. Each order's admin page lists what was sent.

demo1 also gets a small catalog (two categories, three products, generated images) the first time it has no products.
MinIO is published on host port 9100, not 9000, which other software often occupies; after pulling this change run
`docker compose up -d minio`.

On Windows use `mvnw.cmd` and `scripts\tailwind.cmd`.

```bash
./mvnw verify                      # Tailwind build + unit tests + Testcontainers integration tests
./scripts/tailwind.sh --watch      # rebuild CSS while editing templates
```
