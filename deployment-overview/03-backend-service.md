# 03 — Backend container

The backend is the only thing built from this repo. Everything about it is
boring on purpose; the interesting parts are the *environment contract* and the
*log mount*, because those are what break silently.

Run all commands below **on the homelab**, from
`deployment-overview/compose/`. Building the image on the server (not on your
Windows machine) also avoids the `./mvnw` executable-bit question in
[`backend/Dockerfile`](../backend/Dockerfile).

---

## 1. What the current Dockerfile does, and its weak spots

```dockerfile
FROM maven:3.9.6-eclipse-temurin-21 AS builder
COPY . .
RUN ./mvnw clean package -DskipTests
FROM eclipse-temurin:21-jre
COPY --from=builder /app/target/*.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]
```

It works. Four things it does not do, in order of how much they will annoy you:

1. **No dependency layer cache.** `COPY . .` before the build means every
   one-line Java change re-downloads the whole Maven dependency graph. On a
   homelab that is minutes, every time.
2. **No `curl`/`wget` in the runtime image**, so a real HTTP health check is
   impossible without adding a package (that is why the compose healthcheck
   uses bash's `/dev/tcp`).
3. **No non-root user.** The app runs as root inside the container.
4. **No JVM flags / no signal handling discussion.** `ENTRYPOINT` in exec form
   is correct: `java` is PID 1 and receives `SIGTERM` from `docker stop`
   directly. If you ever wrap it as `sh -c "java ..."`, you break that (the
   shell is PID 1, `java` never sees `SIGTERM`, and `docker stop` ends in
   `SIGKILL` after 10 s). Because we pass `JAVA_TOOL_OPTIONS` as an env var the
   JVM picks up automatically, you do **not** need a shell wrapper.

A version that fixes 1–3 without changing behaviour (drop it in only if you
want it — this is deployment boilerplate, not app logic):

```dockerfile
# syntax=docker/dockerfile:1
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system app \
 && useradd --system --gid app --home-dir /app app \
 && mkdir -p /app/logs \
 && chown -R app:app /app
COPY --from=builder /app/target/*.jar app.jar
USER app
ENTRYPOINT ["java","-jar","app.jar"]
```

Two cautions if you adopt it:

- `useradd`/`groupadd` must exist in the base image. Verify once:
  `docker run --rm eclipse-temurin:21-jre sh -c 'which useradd groupadd curl'`.
- Non-root + bind-mounted `logs/`: the container user must be able to write
  `/srv/ratewatch/logs`. Either `sudo chown 1000:1000 /srv/ratewatch/logs`
  (match the `app` uid) or keep running as root. Pick one and write it down —
  "logs are empty but the app is healthy" is usually exactly this.

Keep `-DskipTests` in the image build and **run the tests in CI (US-10)**, not
in the image. A build that needs Docker-in-Docker (Testcontainers) is a bad
fit for an image build.

## 2. Environment contract

These are the variables the container is given by `docker-compose.yml`, and
where the app reads them:

| Env var | Read by | Notes |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | Spring | Selects `application-prod.yaml`; also switches logback to JSON file appenders |
| `SERVER_PORT=8080` | `server.port` | `application-prod.yaml` sets no port, so this is what actually decides it |
| `DB_HOST=postgres` | `application-prod.yaml` | Compose DNS name. `localhost` here means *the backend container* |
| `DB_PORT=5432` | same | Container-internal port, not the host's |
| `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | same | Must match the cluster's existing roles (see 02 §5) |
| `REDIS_HOST=redis`, `REDIS_PORT=6379`, `REDIS_PASSWORD` | *(nothing yet)* | Here for when US-05 lands; no Redis client dependency exists yet |
| `ALLOWED_ORIGINS` | `app.cors.allowed-origins` | Defaults to the Vite dev server |
| `TZ` | JVM/logging | Keep host, DB session, and JVM consistent |
| `JAVA_TOOL_OPTIONS` | JVM | `-XX:MaxRAMPercentage=70` (relative to the container's memory limit) |

Because `prod` (unlike `dev`) has **no defaults** for `DB_*`, a missing
variable fails at startup with an unresolved placeholder rather than quietly
connecting to `localhost`. That is the behaviour you want in production — keep
it.

## 3. Build and start

```bash
docker compose build backend
```

Expected: layers ending with `naming to docker.io/library/ratewatch-backend:local`.
First build downloads Maven dependencies; later builds should hit the cached
`dependency:go-offline` layer.

```bash
docker compose up -d backend
```

```bash
docker compose ps
```

Expected: `ratewatch-backend` becomes `(healthy)` after its 60 s
`start_period`; `depends_on` will have waited for Postgres and Redis to be
healthy first (so if the backend is stuck in `Created`, look at the two data
services instead).

```bash
docker compose logs -f --tail=100 backend
```

Expected, in order: Spring banner, `HikariPool-1 - Start completed`, Flyway
`schema "public" is up to date` (or `Successfully applied 1 migration`),
`Tomcat started on port 8080 (http)`, `Started BackendApplication in Xs`.
`Ctrl-C` only detaches; it does not stop the container.

Then verify from the host, not from inside the container (the published port is
loopback-only, which is the point). First confirm the image really has a shell
for the healthcheck — an untested assumption here fails as a permanently
`unhealthy` container:

```bash
docker run --rm eclipse-temurin:21-jre bash -c 'echo bash-ok'
```

Expected: `bash-ok`. If that fails, the compose healthcheck's `/dev/tcp` trick
does not apply to this image and you should add `curl` to the runtime stage
instead.

```bash
curl -s http://127.0.0.1:8081/api/actuator/health
```

Expected: `{"status":"UP"}`. The `/api` prefix comes from
`server.servlet.context-path`, which also prefixes actuator. If you get 404,
you are probably hitting `/actuator/health` without the prefix. If you get
`{"status":"DOWN"}` with health details, the failing component is named in the
JSON (`db`, `redis`, `diskSpace`).

## 4. Logs: two independent sources

| Source | Where | Use |
|---|---|---|
| stdout (CONSOLE appender) | `docker compose logs backend` | Follow along; what the engine keeps per its log-opts caps |
| JSON files (prod profile) | `/srv/ratewatch/logs/app-json.log`, `error-json.log` | Grep/jq, ship to Loki later, survives container recreation |

`logback-spring.xml` rolls these on size (20 MB / 10 MB, 30 days, 2 GB / 1 GB
caps) and writes them asynchronously with `neverBlock: true` for normal logs.
The error file uses `neverBlock: false` on purpose: application errors should
slow the app down rather than be dropped.

Check they are actually being written (a silent mount failure looks identical to
"I have no logs yet"):

```bash
ls -lh /srv/ratewatch/logs
```

Expected: `app-json.log` growing, and `error-json.log` possibly absent until the
first ERROR (the appender is created lazily by some loggers — absence is only
suspicious *after* an error).

Each line should be valid JSON with `app_name` and `env` fields:

```bash
tail -n 1 /srv/ratewatch/logs/app-json.log | jq -r '.level, .logger_name'
```

(`jq` may need `sudo apt install jq` first.)

## 5. Restart, update, rollback

Restart is cheap and exercises the resilience path you care about (US-03):

```bash
docker compose restart backend
```

Update after a `git pull` on the server:

```bash
docker compose build backend && docker compose up -d backend
```

Compose recreates only the container whose image changed. Postgres keeps
running, which means you also get to observe what your app does when its
database stayed up but it did not — that is exactly the startup path worth
testing.

Tag what you deploy so a rollback is possible:

```bash
APP_VERSION=2026-11-23 docker compose build backend
APP_VERSION=2026-11-23 docker compose up -d backend
```

Rollback then means starting the previous tag, which requires that the image is
still present on the host. Docker deletes nothing automatically, but a
well-meaning `docker system prune -a` does.

⚠ Do not reach for `docker compose down -v` "to make it clean". `-v` deletes
`ratewatch_pgdata`.

## 6. Shutdown behaviour (worth thirty seconds of thought)

`docker stop` sends `SIGTERM`, waits 10 s, then kills. With `java` as PID 1
that signal arrives correctly, but Spring's default is to stop accepting new
requests and exit — it does not wait for an in-flight scheduled fetch to
finish. If you care about not abandoning a fetch mid-write, that is a
deliberate app-level decision (`server.shutdown: graceful` plus a lifecycle
timeout), and it belongs in `application.yml` with a comment, not in this
compose file. Note it in AGENTS.md §8 when you decide.

Next: [`04-ssh-access-model.md`](04-ssh-access-model.md).
