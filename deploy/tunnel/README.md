# Test server on an always-on machine

The whole platform (app, PostgreSQL, MinIO, Mailpit) runs with Docker on one machine. A Cloudflare Tunnel puts it
online: the machine opens the connection outward, so it needs no public IP address and no open ports, and Cloudflare
supplies HTTPS. It is for testing, not for real shops: there are no backups unless you take them, and the site is
down whenever the machine is.

What you need:

- **A domain on Cloudflare** (free plan). Stores open at `<slug>.<domain>`, which needs a wildcard, so
  Cloudflare's free `trycloudflare.com` addresses will not do. Buy a domain anywhere and point its nameservers at
  Cloudflare, or buy it from Cloudflare. Cloudflare's free certificate covers `<domain>` and `*.<domain>`.
- **The machine:** Docker (Docker Desktop on Windows), about 2 GB of free memory and a few GB of disk.

The examples use `example.com`; put your domain in its place. Windows commands are for PowerShell.

## 1. The machine

- Install Docker Desktop and turn on *Start Docker Desktop when you sign in*. Docker Desktop runs only while
  someone is signed in, so let the machine sign in by itself after a restart.
- Power settings: never sleep, and do nothing when the lid closes.
- Copy this repository to it (`git clone`). Only `deploy/tunnel` and `docker/postgres/init` are used there.

## 2. The app image

On the development machine, in the repository:

```bash
./mvnw verify                       # or ./mvnw -DskipTests package
docker build -t ecommerce:latest .
docker save ecommerce:latest -o ecommerce-image.tar
```

Copy `ecommerce-image.tar` to the server and load it:

```powershell
docker load -i ecommerce-image.tar
```

## 3. The tunnel

In `deploy/tunnel` on the server. cloudflared runs from its Docker image, so nothing needs installing:

```powershell
$cf = "cloudflare/cloudflared:2025.10.0"
docker run --rm -it -v "${PWD}/cloudflared:/home/nonroot/.cloudflared" $cf tunnel login
```

It prints a link: open it in a browser, sign in to Cloudflare and choose the domain. Then:

```powershell
docker run --rm -v "${PWD}/cloudflared:/home/nonroot/.cloudflared" $cf tunnel create shop
docker run --rm -v "${PWD}/cloudflared:/home/nonroot/.cloudflared" $cf tunnel route dns shop example.com
docker run --rm -v "${PWD}/cloudflared:/home/nonroot/.cloudflared" $cf tunnel route dns shop "*.example.com"
```

`tunnel create` prints the tunnel's id and writes `cloudflared/<id>.json`, the tunnel's credentials. The two
`route dns` lines add the DNS records for the main site and for every subdomain. Copy
`cloudflared/config.example.yml` to `cloudflared/config.yml` and put in the tunnel id (twice) and your domain
(three times).

`cloudflared/` holds secrets (`cert.pem`, `<id>.json`); git ignores everything in it but the example.

## 4. Settings

Copy `.env.example` to `.env` and fill it in: the domain, a long random value for every `CHANGE_ME`
(`openssl rand -hex 24` in Git Bash), and the first platform admin's email and password. Keep `.env` on the server
only.

## 5. Start

```powershell
docker compose up -d
docker compose logs -f app          # wait for "Started EcommerceApplication", then Ctrl+C
```

On the first start PostgreSQL creates the database and its roles, the app creates the tables, the image bucket and
the first platform admin. Then open:

| What | Address |
|---|---|
| Main site, sign-up | https://example.com |
| Merchant admin | https://example.com/admin |
| Driver view | https://example.com/driver |
| A store | https://<slug>.example.com |
| Platform admin | https://admin.example.com/platform/login |
| Emails the app sent | http://localhost:8025, on the server only |

Once you have signed in as the platform admin, delete the `PLATFORM_ADMIN_*` lines from `.env` and run
`docker compose up -d` again.

There is no demo data: sign up a store, add a payment method, a delivery zone and a product, and go live from the
setup wizard.

## Day to day

- **Texts** (order updates, checkout codes when a store requires them) are not sent: no SMS provider is connected.
  They are written to the app's log in full, so you can pass codes on to testers:
  `docker compose logs app | Select-String "Message to"`.
- **Emails** land in Mailpit on the server (http://localhost:8025), not in real inboxes, so password reset links
  reach only someone with access to the server.
- **A new version:** build and load the image again (step 2), then `docker compose up -d`. The database keeps its
  data; new migrations run on start.
- **Stop:** `docker compose down`. Data stays in the Docker volumes `ecommerce-test_postgres-data` and
  `ecommerce-test_minio-data`. `docker compose down -v` deletes it, as does a Docker Desktop reset.
- **A copy of the database:**
  `docker compose exec postgres pg_dump -U postgres -Fc -f /tmp/ecommerce.dump ecommerce`, then
  `docker compose cp postgres:/tmp/ecommerce.dump .` (not `>`, which in Windows PowerShell spoils the file).
  `docs/operations.md` covers restoring it.
- **Who can see it:** everyone with the address. To keep it to your testers, put a Cloudflare Access policy
  (Zero Trust, free for up to 50 people) on `example.com` and `*.example.com`, letting in only their emails.
