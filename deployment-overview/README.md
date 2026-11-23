# Deployment overview — RateWatch homelab

This directory is the operator's manual for running RateWatch on the Ubuntu
homelab. It is deliberately separate from `AGENTS.md` (which is about *how we
work on the code*) and from `backend/pom.xml` (which is about *what the app
needs*). Here we only answer: **which containers exist, why, and how do I
start/verify/repair them.**

Read in order the first time. After that, `06-operations-runbook.md` is the
only file you will open during an incident.

| # | File | Answers |
|---|------|---------|
| 0 | `README.md` (this file) | What runs where, what is still missing, start order |
| 1 | [`01-host-prerequisites.md`](01-host-prerequisites.md) | What must be true of the Ubuntu host before any container |
| 2 | [`02-compose-data-services.md`](02-compose-data-services.md) | Postgres + Redis: compose file, `.env`, first boot, verification |
| 3 | [`03-backend-service.md`](03-backend-service.md) | Building and running the Spring Boot container |
| 4 | [`04-ssh-access-model.md`](04-ssh-access-model.md) | Reaching these ports without exposing them (tunnels) |
| 5 | [`05-observability-optional.md`](05-observability-optional.md) | Prometheus + Grafana, and the log files that already exist |
| 6 | [`06-operations-runbook.md`](06-operations-runbook.md) | Troubleshooting one command at a time, backups, upgrades |

Actual files that are meant to be copied/used live in
[`compose/`](compose/docker-compose.yml).

---

## 1. Target topology

```
                        your dev machine
                   ┌───────────────────────┐
   ssh -L tunnel ──┤  localhost:5432/6379  │  (tunnel endpoint, not a listener)
                   │  localhost:8081       │
                   └───────────┬───────────┘
                               │ ssh (encrypted, port 22 only)
═══════════════════════════════╪═══════════════════════════ homelab (Ubuntu)
                               │
                  ┌────────────┴─────────────┐
                  │  docker network:         │
                  │  ratewatch_default       │
                  │                          │
   ┌──────────────┴───┐   ┌──────────────┐   │   ┌──────────────────┐
   │ postgres:17      │   │ redis:7.4    │   │   │ backend          │
   │ 127.0.0.1:5432   │   │ 127.0.0.1:6379│  │   │ 127.0.0.1:8081   │
   │ volume: pgdata   │   │ no volume    │   │   │ bind: logs/      │
   └──────────────────┘   └──────────────┘   │   └────────┬─────────┘
                                             │            │ HTTPS out
                                             │            ▼
                                             │   open.er-api.com        (external)
                                             │   Supabase Cloud free tier (external,
                                             │   trigger row only — NOT a container)
                                             └──────────────────────────────────────
```

Two things worth internalising:

- **Nothing listens on the LAN.** Every published port is bound to
  `127.0.0.1`, so the host's own loopback is the only network entry point and
  SSH is the only way in. This is why the debugging instructions throughout
  these docs say "run this *on the server*".
- **Only Postgres is stateful.** If you delete the Redis volume, nothing is
  lost. If you delete `pgdata`, the history is gone. That asymmetry should
  drive how you spend your backup effort.

## 2. Container inventory

| Container | Image | Host binding | Persistence | Health check | Exists for |
|---|---|---|---|---|---|
| `ratewatch-postgres` | `postgres:17` | `127.0.0.1:5432` | named volume `ratewatch_pgdata` | `pg_isready` | US-04 history = source of truth |
| `ratewatch-redis` | `redis:7.4-alpine` | `127.0.0.1:6379` | **none, on purpose** | `redis-cli ping` | US-05 latest-rate cache (TTL) |
| `ratewatch-backend` | built from `backend/Dockerfile` | `127.0.0.1:8081 → 8080` | bind mount `/srv/ratewatch/logs` | TCP connect to `:8080` | US-01..08 |
| `ratewatch-prometheus` | `prom/prometheus` | `127.0.0.1:9090` | named volume | — | optional metrics (US-10 adjacent) |
| `ratewatch-grafana` | `grafana/grafana` | `127.0.0.1:3000` | named volume | — | optional dashboards |

Deliberately **not** containers:

| Thing | Why not |
|---|---|
| Supabase | AGENTS.md scope: Cloud free tier only, thin realtime trigger layer. No self-hosting. |
| `open.er-api.com` | External dependency. Its failure is US-03/US-09's whole subject. |
| Frontend | Out of this repo's deployment scope for now; `ALLOWED_ORIGINS` is the only coupling. |

## 3. Where does the backend actually run?

You have two valid setups. Pick one consciously — most "connection refused"
confusion in this project comes from mixing them.

| | Backend in Docker (recommended once US-04 works) | Backend on your laptop |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | `dev` |
| `DB_HOST` | `postgres` (compose service name) | `localhost` (through an SSH tunnel) |
| `REDIS_HOST` | `redis` | `localhost` (through an SSH tunnel) |
| App port | container `:8080`, host `127.0.0.1:8081` | `APP_PORT=8081` directly |
| Logs | `/srv/ratewatch/logs/*.log` + `docker compose logs` | `backend/logs/*.log` + console |
| Best for | testing resilience (restart, memory limits, real network hop) | fast iteration, debugger |

Rule of thumb: **the hostname inside the container is a service name, never
`localhost`.** That single sentence prevents the most common homelab error.

## 4. Blockers in the repo before the first deploy

Found while cross-checking `backend/pom.xml`, the YAML profiles, and the
Dockerfile against the described architecture. None of these are deployment
details you can work around in compose — they are app-level gaps.

1. **No Redis client on the classpath.** `spring-boot-starter-data-redis` is
   absent, so US-05 cannot work. There is also no `spring.data.redis.*` config
   in any profile, only `DB_*` variables.
2. **The `prometheus` actuator endpoint is exposed but cannot exist.**
   `management.endpoints.web.exposure.include` lists `prometheus`, yet
   `micrometer-registry-prometheus` is not a dependency. In Boot 4 the actuator
   starter brings `micrometer-core`, not a registry — so the endpoint 404s
   until you add the registry.
3. **Flyway version mismatch.** `spring-boot-starter-flyway` (Boot 4.2.0)
   manages Flyway **12.11.0**, but the POM pins
   `flyway-database-postgresql:13.9.0`, whose own POM requires
   `flyway-core:13.9.0`. Maven will keep `flyway-core` at the managed 12.11.0,
   so a module/core version split is almost certain on startup. Either drop the
   explicit version (let the BOM own it) or pin both to the same version.
4. **`application-prod.yaml` has no `server.port`.** It therefore listens on
   8080. That is fine — the compose file pins `SERVER_PORT: 8080` and maps
   8081→8080 — but you must know it before writing health checks or tunnels.
5. **`.env.example` lags the runtime contract.** No `REDIS_*`, no
   `ALLOWED_ORIGINS`, no Supabase/API variables.
6. **`infra/docker/docker-compose.yml` does not exist.** AGENTS.md §1 names it
   as the compose location, but the directory is absent. Until you decide,
   [`compose/docker-compose.yml`](compose/docker-compose.yml) is the real file;
   moving it to `infra/docker/` later only requires updating the `build.context`
   (`../../backend` still resolves correctly from `infra/docker/`, so the move
   is safe) and the `LOG_DIR` default.
7. **Leftover `logging.level.org.springframework.security: DEBUG`** in
   `application-dev.yaml` while no security starter is present. Harmless, but
   it is a false signal that auth exists.

## 5. First-run checklist

Run top to bottom; each step ends with something you can *see*.

- [ ] Host ready: docker engine + compose v2 installed, user in `docker` group,
      `/srv/ratewatch` created → [`01-host-prerequisites.md`](01-host-prerequisites.md)
- [ ] `compose/.env` created from `env.example`, secrets replaced
- [ ] `docker compose up -d postgres redis`; `docker compose ps` shows both
      `(healthy)`
- [ ] Connect once through a tunnel and confirm `rate_history` exists after the
      backend has run its first Flyway migration
      → [`02-compose-data-services.md`](02-compose-data-services.md)
- [ ] Backend image builds and starts; `/api/actuator/health` returns `UP`
      → [`03-backend-service.md`](03-backend-service.md)
- [ ] Tunnel booked in `~/.ssh/config`, reconnect works after a laptop sleep
      → [`04-ssh-access-model.md`](04-ssh-access-model.md)
- [ ] Optional: Prometheus scrapes `/api/actuator/prometheus`
      → [`05-observability-optional.md`](05-observability-optional.md)
- [ ] First backup taken **and restored into a scratch DB** (a backup you have
      never restored is a hope, not a backup)
      → [`06-operations-runbook.md`](06-operations-runbook.md)

## 6. Conventions used by every file here

- Commands that run **on the homelab** are shown inside an ssh session and
  assume the repo is at `~/ratewatch` and compose runs from
  `deployment-overview/compose/`.
- Commands that run **on your machine** connect to `localhost:<forwarded port>`
  and are marked as such.
- Any command that can destroy data (`down -v`, `volume rm`, `pg_restore`
  into the live DB) is called out explicitly. If you see one without a warning,
  treat it as a documentation bug.
