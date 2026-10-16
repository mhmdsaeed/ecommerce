---
name: verify
description: Run the ecommerce app locally and drive it over HTTP on its real hosts (storefronts, main site, platform admin) to verify a change at runtime.
---

# Verify this app at runtime

Surface: an HTTP server whose behavior depends on the **Host** (`<slug>.lvh.me` storefront, `lvh.me` main site with
`/signup`, `/admin`, `/driver`, `admin.lvh.me` platform admin under `/platform`). Always send real hosts.

## Launch (Windows, Git Bash)

```bash
docker ps | grep ecommerce-postgres-1                      # Postgres on host port 5433; else: docker compose up -d postgres
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"   # default JAVA_HOME is JDK 20
./mvnw -o -B -ntp spring-boot:run -Dspring-boot.run.profiles=local -Dtailwind.skip > <scratch>/app.log 2>&1   # run in background
# wait for "Started EcommerceApplication", then a few seconds more: seeding runs after that line
```

Port 8080 must be free. Stopping the background task can leave the JVM running: find the owner of port 8080
(`Get-NetTCPConnection -LocalPort 8080`) and stop it if it is the jdk-21 `java.exe`.
Online Maven needs `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (TLS interception on this machine).
MinIO must answer on IPv4 port 9100 (`curl http://127.0.0.1:9100/minio/health/live` → 200). If the container still
publishes 9000, run `docker compose up -d minio`: here `ipqservice` owns `127.0.0.1:9000`, and the JVM's uploads fail.

## Drive

```bash
R="--resolve lvh.me:8080:127.0.0.1 --resolve admin.lvh.me:8080:127.0.0.1 --resolve demo1.lvh.me:8080:127.0.0.1"
csrf() { grep -oE 'name="_csrf" value="[^"]+"' | head -1 | sed -E 's/.*value="([^"]+)"/\1/'; }
T=$(curl -s $R -c jar -b jar http://lvh.me:8080/admin/login | csrf)      # GET the form first: session + CSRF token
curl -s $R -c jar -b jar --data-urlencode "_csrf=$T" -d "store=demo1&username=owner@demo1.test&password=demo-pass-123" \
     -o /dev/null -w '%{http_code} %{redirect_url}\n' http://lvh.me:8080/admin/login
```

- Demo credentials (local profile seed) are in README.md. Platform admin: `admin@lvh.me` / `local-admin-pass`.
- Send `Accept: text/html` to see error pages; plain curl gets Boot's JSON error body.
- **HTMX requests** (merchant admin catalog): send `-H "HX-Request: true" -H "X-CSRF-TOKEN: $T"`, with the token
  taken from the page's `<body hx-headers=...>`:
  `T=$(grep -oE 'X-CSRF-TOKEN&quot;: &quot;[^&]+' page.html | sed -E 's/.*&quot;: &quot;//')`. Responses are fragments
  (no `<html`). Uploads: `-F "files=@photo.jpg;type=image/jpeg"`. Image URLs point at `http://localhost:9100/media/...`.
- **Arabic form values:** Git Bash mangles non-ASCII curl arguments into `?`. Pass them pre-percent-encoded with `-d`
  (e.g. `-d price=%D9%A1%D9%A5%D9%A5`), or put a percent-encoded body in a file
  (written with the Write tool) and post it with `--data-binary @file`.
- **Storefront cart** (`demo1.lvh.me`): no session and no token in the page. `GET /` sets an `XSRF-TOKEN` cookie;
  send it back as `-H "X-XSRF-TOKEN: $X"` with `-H "HX-Request: true"` on `POST /cart/items`
  (`variantId`, `quantity`), `PATCH`/`DELETE /cart/items/{itemId}`.
  `X=$(grep XSRF-TOKEN jar | awk '{print $7}')`; variant ids are in the product page's `data-variants`
  (`&quot;`-escaped JSON). The cart lives in the `cart` cookie (HttpOnly), and the badge count is in `cart_count`.
  Stock is set at `/admin/inventory/{variantId}` (`onHand`, empty = untracked); restore what you change.
- **Delivery** (M7): zones at `/admin/delivery` (owners and managers; HTMX like the catalog). Zone ids: split the
  page on `<tr>` and take the row's `/admin/delivery/<id>/edit` link; don't grep by nearby text, rows are long.
  The cart's delivery box is `GET /cart/delivery?governorate=&city=&area=` with `HX-Request` (no CSRF); the choice
  lands in the `delivery_to` cookie. demo1 seeds Damascus city, Mazzeh, all of Rif Dimashq and Aleppo ($).
- **Checkout and orders** (M8, M9): `/checkout` is a plain form carrying `_csrf` and `idempotencyKey` (take both
  from the page); POST it with `name`, `phone`, `governorate`, `city`, `landmark`, `paymentMethod` (COD or
  SHAM_CASH on demo1) → 302 to `/orders/<n>?t=<token>`. The proof form there is multipart (`-F _csrf=... -F
  reference=... -F screenshot=@file`). Admin actions are plain posts with the session `_csrf` (take it from any
  admin page's logout form): `/admin/orders/<n>/status` (`target`, `note`), `/driver` (`driverId`), `/proof`
  (`decision=approve|reject`, `note`), `/block` (`blocked`, `reason`; owners and managers). Orders cannot be deleted
  (the event log is append-only), so test orders stay in the demo data. A helper script that does all of this is
  worth writing to the scratchpad and sourcing, since shell state does not persist between calls.
- Cookies are host-only per site; to test session replay across hosts, send `-H "Cookie: JSESSIONID=<id>"` by hand.
- Check row-level security outside the app:
  `docker exec -e PGPASSWORD=app_user_local ecommerce-postgres-1 psql -h localhost -U app_user -d ecommerce`
  (no `app.tenant_id` → tenant tables return 0 rows).

## Gotchas

- Rate limits are in memory and all local curls come from 127.0.0.1, so restart the app if you hit them (429, or
  "locked" on login). Logins: 5 failures lock an account for 15 minutes, 30 attempts per address per 5 minutes.
  Storefront (M15): 10 verification texts and 20 orders per hour, 120 cart changes per minute; API: 300 per minute.
  The full list is in `docs/operations.md`.
- **REST API** (M14): `/api/v1` on `lvh.me` only. `GET /api/v1/stores/<slug>` gives the id; send it as
  `X-Store-Id` to `/api/v1/products` and `/api/v1/products/<id>`. Docs at `/api/docs`. Errors are problem JSON.
- **Production image** (M15): see `docs/operations.md`. Under the `prod` profile plain http is redirected (308) and
  cookies are `Secure`, so send `X-Forwarded-Proto: https` and carry cookies by hand (curl drops Secure cookies
  over http).
- Runs write to the local dev database (signups, staff). Use fresh slugs (`name$RANDOM`).
- **Passwords:** a reset link for a demo account arrives in Mailpit (`/api/v1/search?query=to:<email>`, the link's
  `&` is `&` in its JSON). Changing a demo password breaks the README credentials: set it back on
  `/admin/account` (`demo-pass-123`). A password change ends that account's other sessions.
