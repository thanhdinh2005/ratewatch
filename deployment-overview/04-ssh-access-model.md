# 04 — SSH access model

AGENTS.md §1 is explicit: you reach the homelab over SSH port forwarding, not
by exposed ports. This file makes that mechanical, because the single most
common wasted hour in this project is running a `localhost` command on the
wrong machine.

---

## 1. The rule

| Where you are | What `localhost:5432` means |
|---|---|
| On the **homelab** (inside an ssh session) | The host's loopback → forwarded to the Postgres container by Docker |
| On your **dev machine**, tunnel active | The local end of the SSH tunnel → the same container |
| On your **dev machine**, no tunnel | Nothing. Connection refused, and that is correct |
| Inside the **backend container** | The backend container itself. Postgres is `postgres:5432`, never `localhost` |

Every command in these docs is written to make its location unambiguous. If a
command says `docker compose`, it runs on the server.

## 2. One SSH config block, not three ad-hoc `-L` flags

On your dev machine, edit `~/.ssh/config` (Windows:
`C:\Users\<you>\.ssh\config`):

```sshconfig
Host ratewatch
    HostName 192.168.1.50          # or your Tailscale/DDNS name
    User youruser
    IdentityFile ~/.ssh/id_ed25519
    ServerAliveInterval 30
    ServerAliveCountMax 3
    ExitOnForwardFailure yes
    LocalForward 5432 127.0.0.1:5432
    LocalForward 6379 127.0.0.1:6379
    LocalForward 8081 127.0.0.1:8081
    LocalForward 9090 127.0.0.1:9090
    LocalForward 3000 127.0.0.1:3000
```

Why each non-obvious line:

- `ServerAliveInterval 30` + `CountMax 3` — keeps NAT/firewall state alive and
  makes a dead connection fail in ~90 s instead of hanging forever. Without it
  you will at some point debug "Postgres is down" when the tunnel is simply
  stale.
- `ExitOnForwardFailure yes` — if a local port is already bound, ssh exits
  immediately instead of opening a silent half-working session. This turns a
  confusing failure into a clear one.
- Forwards point at `127.0.0.1:PORT` **on the server**, matching the compose
  file's `127.0.0.1:` bindings. Forwarding to `localhost` is the same thing but
  the explicit form documents the dependency.

Then one command opens everything:

```bash
ssh -N ratewatch
```

`-N` = no shell, just forwards. Keep it in its own terminal (or run it in the
background) and use ordinary `ssh ratewatch` in another terminal when you need
a shell.

## 3. Verifying a tunnel, without guessing

On your dev machine (Windows PowerShell):

```powershell
Test-NetConnection -ComputerName 127.0.0.1 -Port 5432 -InformationLevel Quiet
```

Expected: `True`. `False` means either the tunnel is not running or the
container is not publishing the port — those are different problems, so check
in this order:

1. Is the ssh process alive? `Get-Process ssh` (Windows) / `ps aux | grep 'ssh -N'`.
2. On the server: `docker compose ps` — is `ratewatch-postgres` up with a
   `127.0.0.1:5432->5432/tcp` port mapping?

If (2) is fine but (1) shows no ssh, it was the tunnel. That two-step check is
the whole debugging model; everything else in the runbook is a variation of it.

On Linux/macOS the same check is `nc -z 127.0.0.1 5432 && echo open`.

## 4. Talking to Postgres over the tunnel

Two equivalent styles — prefer whichever keeps you honest about *where* you are.

**Style A: client on your machine, through the tunnel** (needs `psql` locally;
gut feel: "I am inspecting my homelab DB from my desk")

```bash
psql -h 127.0.0.1 -p 5432 -U postgre -d ratewatch_db
```

**Style B: nothing local, run it inside the container** (no client install, and
it cannot accidentally target a different DB)

```bash
# on the server
docker compose exec postgres psql -U postgre -d ratewatch_db
```

Whichever you use, `\dt` should list `rate_history` once the backend has run
Flyway, and `select count(*) from rate_history;` should be non-zero once
US-04 works. That query is your fastest end-to-end health signal.

For a GUI (DBeaver/DataGrip), point it at `127.0.0.1:5432` with the tunnel up.
Do **not** put the homelab IP in it — if it ever works without the tunnel, your
compose file is no longer loopback-bound and that is a bug worth finding.

## 5. When the backend runs on your machine instead

`application-dev.yaml` defaults already assume this: `DB_HOST` defaults to
`localhost`, `APP_PORT` to `8081`. `.env` at the repo root matches. So:

```powershell
# dev machine, tunnel up, in backend/
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The app then connects through `localhost:5432` → tunnel → container. Same
database, same Flyway history, no divergence between "dev schema" and "prod
schema" — which is the entire reason to do it this way instead of running a
second Postgres locally.

The one thing to watch: **`spring.jpa.hibernate.ddl-auto: validate`** means the
app refuses to start if the entities and the migrated schema disagree. That is
a feature, and it will fire the first time you add an entity without a
migration.

## 6. Tunnels for the other services

| Local port | Purpose | Verify |
|---|---|---|
| 8081 | backend API + actuator | `curl http://127.0.0.1:8081/api/actuator/health` |
| 5432 | Postgres | `psql ... -c 'select 1'` |
| 6379 | Redis (if you add a client) | `redis-cli -a "$REDIS_PASSWORD" ping` |
| 9090 | Prometheus UI | open in browser |
| 3000 | Grafana UI | open in browser |

Redis has a password, so `redis-cli` through the tunnel needs `-a` (or
`REDISCLI_AUTH` in your shell). That prompt-on-the-command-line leak is a
dev-machine concern only; nothing is exposed to the LAN either way.

## 7. Gotchas that look like infrastructure failures

| Symptom | Cause | Fix |
|---|---|---|
| `bind: Address already in use` when ssh starts | A local Postgres/Redis/Docker Desktop is already on that port | Change the **local** side: `ssh -L 15432:127.0.0.1:5432 ratewatch` and connect to 15432 |
| Tunnel worked, now "connection refused" | Laptop slept; ssh died | Re-run `ssh -N ratewatch`; `ServerAliveInterval` makes this detectable sooner, not immune |
| `psql: FATAL: no pg_hba.conf entry ...` | You are connecting from a container/host address, not loopback | You bypassed the tunnel. Re-check `-h 127.0.0.1` |
| Browser CORS error on the frontend | `ALLOWED_ORIGINS` does not include the origin you are actually browsing | Set it in `compose/.env`, then `docker compose up -d backend` |
| Tunnel to 8081 works but `/` is 404 | Context path is `/api` | Use `/api/...` |

## 8. Optional: always-on access instead of a tunnel

If you later want the dashboard reachable from a phone, the homelab-idiomatic
answer is a mesh VPN (Tailscale/WireGuard) plus a reverse proxy — not publishing
these ports. That changes the security model from "only ssh" to "only my
devices", which is still fine, but it is a decision to make explicitly and
record in AGENTS.md, not something to stumble into by removing a `127.0.0.1:`
prefix.

Next: [`05-observability-optional.md`](05-observability-optional.md).
