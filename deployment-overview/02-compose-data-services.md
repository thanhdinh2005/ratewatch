# 02 — Postgres + Redis containers

These two were decided long ago (AGENTS.md §1): they run as Docker
containers, never as apt packages. This file is about making that concrete and
verifiable.

Files involved:

- [`compose/docker-compose.yml`](compose/docker-compose.yml) — the stack
- [`compose/env.example`](compose/env.example) — the variable contract
- `backend/src/main/resources/db/migration/V1__init.sql` — what the schema
  *wants* to be; Flyway is what actually creates it, on first backend start

---

## 1. How Compose consumes variables (read this once, it explains three bugs)

Compose reads a file named `.env` **next to the compose file** and substitutes
`${VAR}` inside `docker-compose.yml`. It does *not* pass those variables into
containers automatically — that only happens for variables listed under a
service's `environment:` block. Both halves are already wired in our file
(`${DB_NAME}` both interpolates and is passed to the backend).

Consequences worth remembering:

- Editing `compose/env.example` changes nothing. `compose/.env` is the live one.
- `docker compose config` prints the file *after* substitution — the fastest
  way to see what Compose actually thinks your variables are, secrets included.
  Do not paste its output into a chat or a ticket.

## 2. Create the environment file

On the homelab, from the repo:

```bash
cd deployment-overview/compose
cp env.example .env
```

Generate real secrets instead of typing `secret` again:

```bash
openssl rand -base64 24
```

Run it twice — once for `DB_PASSWORD`, once for `REDIS_PASSWORD` — and paste
each into `.env`. Then:

```bash
chmod 600 .env
```

Now check substitution *before* starting anything:

```bash
docker compose config --quiet
```

Expected: no output, exit code 0. Any `variable is not set` warning means the
name in the compose file and the name in `.env` drifted apart; fix that now,
because the failure mode otherwise is a container that starts with an empty
password.

## 3. Why each line in the Postgres service exists

| Setting | Reason |
|---|---|
| `image: postgres:17` | Must match `TestcontainersConfiguration` (`postgres:17`). If tests run on a different major than your homelab, a schema that passes CI can fail in production (type/locale/`gen_random_uuid` differences). Bump both together. |
| `restart: unless-stopped` | Survives a host reboot; stays down if *you* stopped it deliberately. `always` would ignore your intent. |
| `POSTGRES_DB/USER/PASSWORD` | **Only applied when the data directory is empty.** Read §5. |
| `PGTZ: UTC` | The column is `timestamptz`; keeping the server session in UTC removes one source of "why is this 7 hours off". |
| `ports: "127.0.0.1:5432:5432"` | The `127.0.0.1:` prefix is the whole security model. Without it, Docker's iptables rules can expose the port to the LAN regardless of ufw. |
| named volume `pgdata` | Not a bind mount. See §6. |
| `pg_isready` healthcheck | Proves the server accepts connections, not merely that the process is alive. `depends_on: condition: service_healthy` on the backend is built on this. |

## 4. Why Redis looks unusual (it is intentional)

```yaml
command: redis-server --requirepass ... --maxmemory 256mb \
         --maxmemory-policy allkeys-lru --save "" --appendonly no
environment: REDISCLI_AUTH: ...
```

- **No persistence (`--save ""`, `--appendonly no`).** Redis is a read
  optimisation, never the only copy (AGENTS.md §4). Persisting it would invite
  the belief that it is durable, and it would add a second thing to back up for
  zero benefit. After a restart the cache is cold, a read misses, and it falls
  back to Postgres — exactly the degraded-but-correct path we want to be able
  to demonstrate.
- **`--maxmemory` + `allkeys-lru`.** A cache with no ceiling is a memory leak
  with extra steps. When the cap is hit Redis evicts, which for
  latest-rate-per-currency keys is harmless (they get re-read from Postgres).
  Do not use `noeviction`: writes would start failing instead.
- **`--requirepass`.** Even though the port is loopback-only, a password means
  a wrong `REDIS_HOST` in some future config fails loudly instead of silently
  talking to an unrelated Redis.
- **`REDISCLI_AUTH`** lets the healthcheck run `redis-cli ping` without
  `-a <password>`, which would otherwise show up in the container's process
  list.

## 5. The initialisation trap (the #1 support question about this stack)

`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` are consumed **once**, by
the entrypoint, when `/var/lib/postgresql/data` is empty. Afterwards:

- changing them in `.env` does nothing to the existing cluster;
- the backend then fails authentication while the container reports healthy.

Symptoms and the honest fixes:

| Symptom | Reality | Fix |
|---|---|---|
| Backend `password authentication failed` and container is healthy | Password in `.env` ≠ password in the cluster | `ALTER ROLE` (below), or recreate the volume if you truly have no data |
| `database "ratewatch_db" does not exist` | `DB_NAME` changed after init | `CREATE DATABASE` or recreate volume |
| Healthcheck says healthy but nothing connects | `pg_isready` only checks the server, not the credentials | Use `psql -U ... -c 'select 1'` as the real test (§7) |

Changing the password of an existing cluster, without destroying data. Note
that the superuser here is `POSTGRES_USER` (your `postgre`), **not** `postgres`
— there is no `postgres` role in a cluster initialised this way. Shell
variables need the `.env` loaded first:

```bash
set -a && . ./.env && set +a
docker compose exec postgres psql -U "$DB_USERNAME" -d "$DB_NAME" -c \
  "ALTER ROLE \"$DB_USERNAME\" WITH PASSWORD '$DB_PASSWORD';"
```

(A role may always change its own password, so this needs no superuser dance.
Your `DB_USERNAME` is `postgre` per the existing `.env` — note the missing
last `s`. It is only a name; keep it consistent rather than "fixing" it, because
tests and `.env` reference it.)

Destroying the volume (⚠ deletes all rate history — only acceptable before you
have data you care about):

```bash
docker compose down            # stop, keep volume
docker volume rm ratewatch_pgdata
```

Never `docker compose down -v` on a stack you care about; `-v` deletes named
volumes. Make that a habit before it costs you the project's history.

## 6. Why a named volume and not `./data:/var/lib/postgresql/data`

A bind mount looks friendlier but breaks more often: Postgres' entrypoint
`chown`s the data directory, and on a bind mount that fights Docker's
permission model (particularly when the directory was auto-created as root).
It also makes backups ambiguous — you start copying a raw cluster with `cp`,
which is how you get a corrupt backup.

Rule: **named volumes for data, bind mounts for things you want to read from
the host.** That is exactly the split we use (`pgdata` volume, `logs` bind
mount). Backup Postgres through `pg_dump`, never by copying files (§
[`06-operations-runbook.md`](06-operations-runbook.md)).

## 7. First boot, verified one step at a time

Run on the homelab, from `deployment-overview/compose/`. Load `.env` into your
shell once so the `$DB_*` placeholders in the commands below expand (Compose
reads the file itself for `${...}` interpolation, but your *shell* does not):

```bash
set -a && . ./.env && set +a
```

```bash
docker compose up -d postgres redis
```

Expected: two `Started`/`Created` lines. If it prints
`Bind for 127.0.0.1:5432 failed: port is already allocated`, an old Postgres
from an earlier attempt is still running — find it with `docker ps` and stop it
rather than changing the port.

```bash
docker compose ps
```

Expected: both `State: running`, and under `STATUS` the word `(healthy)` after
~30 s. `starting` means the healthcheck has not passed yet — wait, then look
again. `unhealthy` is a real problem; continue to the next command to find out
which part.

```bash
docker compose logs --tail=40 postgres
```

Expected, in order: `PostgreSQL init process complete` (or, on an existing
volume, none of the init lines), then `database system is ready to accept
connections`. A loop of `FATAL: ... does not exist` while staying up usually
means `POSTGRES_DB`/`POSTGRES_USER` disagree with the volume's existing
cluster.

Now prove credentials, not just liveness:

```bash
docker compose exec postgres \
  psql -U "$DB_USERNAME" -d "$DB_NAME" -c 'select current_database(), current_user;'
```

Expected: one row with your DB name and user. If this fails while `ps` says
`healthy`, you have hit §5.

And prove Redis auth works the same way:

```bash
docker compose exec redis redis-cli ping
```

Expected: `PONG`. (No `-a` needed: `REDISCLI_AUTH` is in the container's
environment.) If it says `NOAUTH Authentication required`, `REDISCLI_AUTH` is
missing from the service definition.

Tables will appear only after the backend starts once and Flyway runs:

```bash
docker compose exec postgres \
  psql -U "$DB_USERNAME" -d "$DB_NAME" -c '\dt'
```

Expected after the backend's first start: `rate_history` and
`flyway_schema_history`. Expecting tables before the backend has ever run is
the second most common misunderstanding — **Flyway creates the schema, not
`up -d`.**

## 8. What "healthy" does and does not mean

`(healthy)` = the healthcheck command exits 0. For Postgres that is
`pg_isready`: server accepts *socket* connections. It says nothing about
credentials, the target DB, or whether the backend can reach it. For Redis it
is a `PING`: server alive and accepting the configured auth, nothing about
eviction pressure or latency.

That gap is why the runbook asks you to run one authenticated command
(`psql -c 'select 1'`, `redis-cli ping`) before concluding "the DB is fine".

Next: [`03-backend-service.md`](03-backend-service.md).
