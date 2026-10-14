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

Open http://lvh.me:8080 (Arabic, RTL) or http://lvh.me:8080/?lang=en. `lvh.me` and its subdomains resolve to 127.0.0.1, so each site gets its own host. Health: http://lvh.me:8080/actuator/health.

The `local` profile seeds demo data. These credentials exist only in local databases:

| Where | URL | Login |
|---|---|---|
| Storefronts | http://demo1.lvh.me:8080, http://demo2.lvh.me:8080 | none |
| Merchant admin | http://lvh.me:8080/admin | store `demo1`, `owner@demo1.test` (or staff `staff@demo1.test`), password `demo-pass-123` |
| Driver view | http://lvh.me:8080/driver | store `demo1`, phone `0933000002`, password `demo-pass-123` |
| Platform admin | http://admin.lvh.me:8080 | `admin@lvh.me`, password `local-admin-pass` |
| New store | http://lvh.me:8080/signup | |

On Windows use `mvnw.cmd` and `scripts\tailwind.cmd`.

```bash
./mvnw verify                      # Tailwind build + unit tests + Testcontainers integration tests
./scripts/tailwind.sh --watch      # rebuild CSS while editing templates
```
