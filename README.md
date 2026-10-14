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

Open http://localhost:8080 (Arabic, RTL) or http://localhost:8080/?lang=en. Health: http://localhost:8080/actuator/health.

On Windows use `mvnw.cmd` and `scripts\tailwind.cmd`.

```bash
./mvnw verify                      # Tailwind build + unit tests + Testcontainers integration tests
./scripts/tailwind.sh --watch      # rebuild CSS while editing templates
```
