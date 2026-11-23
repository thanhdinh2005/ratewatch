# 06 — Operations runbook

Symptom → one command → what the output means → next command. No "run all of
this and paste the result": the point is that after two or three incidents you
would have written this file yourself.

All commands run **on the homelab** from `deployment-overview/compose/` unless
marked otherwise. Shell variables from `.env` are not in your environment — when
a command needs them, start with:

```bash
set -a && . ./.env && set +a
```

---

## 1. The 30-second daily check

```bash
docker compose ps
```

Expected: `postgres (healthy)`, `redis (healthy)`, `backend (healthy)`. Then:

```bash
curl -s http://127.0.0.1:8081/api/actuator/health; echo
```

Expected: `{"status":"UP"}`. That is it — two commands, and they distinguish
"everything works" from "something is wrong" without further guessing.

## 2. Symptom-first troubleshooting

### A. A container is not running

```bash
docker compose ps -a
```

What to read: the `STATUS` column, not the names. `Exited (1)` = the process
died and told you why in its logs. `Restarting` = it is crash-looping (the
`unless-stopped` policy keeps trying), so the useful information is in the
first 50 lines of the *current* attempt, not the last.

Then exactly one service's logs:

```bash
docker compose logs --tail=60 backend
```

Read the **first** exception, not the last. Spring Boot prints the root cause
first and then a wall of context. Common first exceptions in this stack and
what each means:

| First exception | Meaning | Next step |
|---|---|---|
| `Could not resolve placeholder 'DB_HOST'` | A required env var did not reach the container | `docker compose config` and compare with the service's `environment:` block |
| `FlywayException: Unsupported Database` or a `NoSuchMethodError` from `org.flywaydb` | Flyway module/core version split (see README §4 item 3) | Fix the POM; do not chase the database |
| `password authentication failed for user "postgre"` | Cluster was initialised with a different password | [`02-compose-data-services.md`](02-compose-data-services.md) §5 |
| `Connection refused` to `postgres:5432` | Backend started before Postgres was reachable, or wrong service name | `docker compose ps` — is Postgres healthy? Is `DB_HOST` literally `postgres`? |
| `Unable to find a @SpringBootConfiguration` | You are running tests, not the app | Unrelated to deployment |

### B. Everything is "up" but no new rates appear

This is the project-specific failure and the most interesting one. Work
outwards from the app:

1. Is the scheduler even running? Look for the log line your scheduler writes:

   ```bash
   jq -r 'select(.logger_name|test("scheduler")) | [.timestamp,.message] | @tsv' \
     /srv/ratewatch/logs/app-json.log | tail -n 5
   ```

   No lines at all → the task is not being scheduled (`@EnableScheduling`
   missing, or the bean is not created). A repeated "retrying in Xs" → it is
   working as designed and the external call is failing.

2. Can the *host* reach the external API?

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' https://open.er-api.com/v6/latest/USD
   ```

   Expected `200`. If this fails, it is the homelab's egress/NAT/DNS, not your
   code — stop looking at the scheduler.

3. Can the *container* reach it? (Different DNS resolver, different network.)

   ```bash
   docker compose exec backend getent hosts open.er-api.com
   ```

   Expected: one or more IPs. Empty output means container DNS is broken while
   the host's is fine — that is a Docker daemon DNS issue, not an app issue.

4. Is the rate actually changing? `open.er-api.com` updates on its own
   schedule; `time_next_update_utc` can legitimately be hours away. Confirm the
   app is respecting it by checking the newest row:

   ```bash
   docker compose exec postgres psql -U "$DB_USERNAME" -d "$DB_NAME" -c \
     'select fetched_at, next_update_at, usd_to_vnd from rate_history order by fetched_at desc limit 3;'
   ```

   If `next_update_at` is in the future, "no new data" is correct behaviour.
   This is the single most common false alarm in the project.

### C. A connection is refused from your dev machine

```powershell
Test-NetConnection -ComputerName 127.0.0.1 -Port 5432 -InformationLevel Quiet
```

`False` → check whether the tunnel process exists, then whether the port is
published on the server (`docker compose ps` shows
`127.0.0.1:5432->5432/tcp`). Fix in that order; it is almost never both.
Full detail: [`04-ssh-access-model.md`](04-ssh-access-model.md) §3.

### D. Postgres will not start at all

```bash
docker compose logs --tail=40 postgres
```

Typical causes, in order of frequency:

- `database files are incompatible with server` → you changed the image tag
  across a major version (see §5).
- `No space left on device` → `df -h /var/lib/docker`, then §4.
- `permission denied` on the data directory → the volume inherited odd
  ownership; restoring from a `pg_dump` into a fresh volume is faster than
  forensic `chown` work.
- `could not create shared memory segment` → host `shm` too small; add
  `shm_size: 256mb` to the service.

### E. The host feels slow or containers get OOM-killed

```bash
docker stats --no-stream
```

Expected: per-container CPU/memory next to their limits. Then:

```bash
sudo dmesg -T | grep -i -E 'oom|killed process' | tail
```

An OOM kill leaves a line here; a slow host does not. If the backend is the
victim, lower `MaxRAMPercentage` or set `mem_limit` so the JVM sizes itself
against a real ceiling ([`01-host-prerequisites.md`](01-host-prerequisites.md)
§7) rather than against all host RAM.

## 3. Backups (and the drill that makes them real)

Postgres holds the only irreplaceable data. Redis holds nothing worth saving.

Nightly logical backup (custom format, compressed, restorable selectively):

```bash
docker compose exec -T postgres pg_dump -U "$DB_USERNAME" -d "$DB_NAME" -Fc \
  > "/srv/ratewatch/backups/ratewatch-$(date +%F).dump"
```

Notes that matter: `-T` disables TTY allocation (otherwise the binary stream is
mangled); the shell redirect happens on the **host**, so the file lands in
`/srv/ratewatch/backups`, not inside the container.

Make it a cron entry for your user (`crontab -e`), 03:00 local, with the
`set -a && . ./.env` preamble and `cd` into the compose directory. Keep 14 days:

```bash
find /srv/ratewatch/backups -name 'ratewatch-*.dump' -mtime +14 -delete
```

A nightly `pg_dump` that fails silently is worse than none, so log the exit code
and check the file size:

```bash
ls -lh /srv/ratewatch/backups | tail -n 5
```

Expected: today's file present, non-trivial size (a few KB once history
accumulates), and yesterday's still there.

**The drill.** Do this once now, not during an incident. Restore into a scratch
database and compare a row count:

```bash
docker compose exec postgres createdb -U "$DB_USERNAME" ratewatch_restore_check
docker compose exec -T postgres pg_restore -U "$DB_USERNAME" -d ratewatch_restore_check \
  < "/srv/ratewatch/backups/$(ls -t /srv/ratewatch/backups | head -n1)"
docker compose exec postgres psql -U "$DB_USERNAME" -d ratewatch_restore_check \
  -c 'select count(*) from rate_history;'
```

Expected: a count close to the live table. Then clean up:

```bash
docker compose exec postgres dropdb -U "$DB_USERNAME" ratewatch_restore_check
```

Restoring over the **live** database (`-d "$DB_NAME"`) destroys current data.
If you ever must, stop the backend first (`docker compose stop backend`) so
nothing writes mid-restore, and take a fresh dump before you start.

Retention reality check: these dumps live on the same disk as the data they
protect. That covers "I dropped a table", not "the disk died". Copying
`/srv/ratewatch/backups` off the box (rsync to your PC, or `rclone` to cloud
storage) is the difference between a backup and a copy.

## 4. Disk and maintenance

```bash
docker system df
```

Expected: `volumes` growing slowly (the DB), `images` accumulating one row per
build. Reclaim build leftovers safely (this does **not** touch named volumes):

```bash
docker image prune -f
```

Do **not** run `docker system prune --volumes` on this host, and do not run
`docker compose down -v` except as a deliberate "delete my history" action.

Check the log directory is not silently ballooning:

```bash
du -sh /srv/ratewatch/logs; ls -lh /srv/ratewatch/logs | tail -n 5
```

`logback-spring.xml` caps it at ~3 GB total, so a much bigger number means the
appender config is not what you think it is.

## 5. Upgrading Postgres majors (the one upgrade that can eat data)

Changing `postgres:17` → `postgres:18` in the compose file is **not** an
upgrade; it is a new server that refuses the old data directory. The safe
procedure, in order:

1. Take and verify a backup (§3), including the restore drill.
2. Stop the backend so nothing writes: `docker compose stop backend`.
3. Dump: the `pg_dump` command from §3, with a new filename.
4. Stop and remove the **container** (keep the volume): `docker compose down`.
5. Change the image tag, `docker compose up -d postgres` so a fresh volume is
   initialised by the new major.
6. Restore the dump into it, then start the backend and confirm
   `flyway_schema_history` says "up to date".

This is why §3 exists before you need it. A `pg_dump` from an older major
restores cleanly into a newer one; a data directory does not.

Also worth knowing: Postgres image majors have changed their `PGDATA` layout
over time. Read the image's release notes before changing the tag, and never
change the tag and the volume in the same hurried step.

## 6. Rotating a secret

Order matters, or you lock yourself out mid-rotation:

1. Change the credential in the running service (`ALTER ROLE ... WITH PASSWORD`
   for Postgres; `docker compose exec redis redis-cli CONFIG SET requirepass`
   for Redis).
2. Update `.env`.
3. `docker compose up -d` so containers pick up the new environment.
4. Re-verify with the authenticated check from
   [`02-compose-data-services.md`](02-compose-data-services.md) §7.

## 7. What to collect before asking for help

If you get stuck for more than ~30 minutes, paste *this*, not a wall of logs:

- the symptom in one sentence ("no new rows since 14:00, backend healthy"),
- `docker compose ps -a` output,
- the **first** exception from the failing service's logs,
- the last successful `rate_history` row's `fetched_at` and `next_update_at`,
- which of the checks above you ran and what they returned.

That list is also what makes future-you effective: it separates "app decided
not to fetch" from "app tried and failed" from "host cannot reach the internet"
before anyone opens a file.
