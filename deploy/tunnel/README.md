# Test server on an always-on machine

The whole platform (app, PostgreSQL, MinIO, Mailpit) runs with Docker on one machine, with nightly backups and
pgAdmin beside it. A Cloudflare Tunnel puts it online: the machine opens the connection outward, so it needs no
public IP address and no open ports, and Cloudflare supplies HTTPS. It is for testing, not for real shops: the site
is down whenever the machine is.

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
(`openssl rand -hex 24` in Git Bash), the first platform admin's email and password, and pgAdmin's. Keep `.env` on
the server only.

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
| The database (pgAdmin) | http://localhost:5050, on the server only |

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
  `ecommerce-test_minio-data`. `docker compose down -v` deletes it, as does a Docker Desktop reset; the backups
  below are what survives that.
- **Who can see it:** everyone with the address. To keep it to your testers, put a Cloudflare Access policy
  (Zero Trust, free for up to 50 people) on `example.com` and `*.example.com`, letting in only their emails.

## Backups

The `backup` service backs up every night at 03:30 Syrian time, after the billing job. Each backup is two files in
`deploy/tunnel/backups`: `ecommerce-<date>_<time>.dump` (the whole database) and `media-<date>_<time>.tar.gz`
(every image). It keeps 14 days and deletes older ones. `BACKUP_DIR`, `BACKUP_TIME`, `BACKUP_KEEP_DAYS` and `TZ` in
`.env` change that; git ignores the folder.

- **Keep a copy off the machine.** Backups on the same disk do not survive a dead disk or a Docker Desktop reset.
  Set `BACKUP_DIR` to a folder OneDrive or Google Drive syncs (for example
  `BACKUP_DIR=C:\Users\<you>\OneDrive\shop-backups`), then `docker compose up -d`.
- **Check it ran:** `docker compose logs backup` shows a `Backup <date>_<time>: ...` line for each.
- **One now** (before an update, say): `docker compose exec backup sh /scripts/backup.sh`.
- **Restore** one, which replaces the whole database and every image with the backup's:

  ```powershell
  docker compose stop app minio
  docker compose exec backup sh /scripts/restore.sh 2026-10-29_0330
  docker compose start minio app
  ```

  Give the date and time from the file names. The script refuses while the app or MinIO is running. Orders and
  sign-ups made after that backup are lost, so take one first if you may want them back.

## The database in pgAdmin

pgAdmin runs at http://localhost:5050, on the server only: sign in with `PGADMIN_EMAIL` and `PGADMIN_PASSWORD`.
The server *Shop database* is already listed; it asks for the database password, which is `POSTGRES_PASSWORD`.

That is the PostgreSQL superuser: it sees every store's rows (row-level security does not apply to it) and can
change or delete anything, so take a backup before editing data by hand. Keep pgAdmin off the tunnel. If you need it
from elsewhere, add `pgadmin.example.com` → `http://pgadmin:80` to `cloudflared/config.yml` (above the wildcard)
only behind a Cloudflare Access policy that lets in your email alone.
