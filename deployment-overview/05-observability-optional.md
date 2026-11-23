# 05 — Observability (optional, but cheap to start)

This whole project is about operational behaviour, so "how would I know it
broke?" is not an optional extra — it is the deliverable of week 4. But the
minimum viable version is small: one health endpoint and one container that
scrapes metrics. Do this after US-04 works, not before.

Two things are already in the repo and cost nothing:

- `management.endpoints.web.exposure.include: health,info,prometheus`
- `logback-spring.xml` writing JSON lines to `/srv/ratewatch/logs`

---

## 0. Blocker: the `prometheus` endpoint does not exist yet

`spring-boot-starter-actuator` in Boot 4.2 brings `micrometer-core` but **no
registry implementation**, so the `prometheus` endpoint listed above has
nothing to expose. Symptom: `/api/actuator/prometheus` returns 404 while
`/api/actuator/health` works fine.

The dependency that fixes it is `io.micrometer:micrometer-registry-prometheus`
(version managed by the Boot BOM — do not pin it). Add it yourself next to the
actuator starter; this is boilerplate, so no learning is lost, but you should
still be able to say out loud what a `MeterRegistry` is before you do.

Confirm the endpoint is live once it is added:

```bash
curl -s http://127.0.0.1:8081/api/actuator/prometheus | head -n 5
```

Expected: `# HELP jvm_...` / `# TYPE ...` text. A 404 means the dependency is
not on the classpath or the container was not rebuilt.

## 1. Start Prometheus + Grafana

Files: [`compose/monitoring/docker-compose.monitoring.yml`](compose/monitoring/docker-compose.monitoring.yml),
[`prometheus.yml`](compose/monitoring/prometheus.yml), and the provisioned
datasource.

Prerequisite: the main stack must have been up once, because the monitoring
project joins its network (`ratewatch_default`).

```bash
cd deployment-overview/compose/monitoring
docker compose -f docker-compose.monitoring.yml up -d
```

```bash
docker compose -f docker-compose.monitoring.yml ps
```

Expected: `ratewatch-prometheus` and `ratewatch-grafana` running. If instead you
get `network ratewatch_default declared as external, but could not be found`,
the main stack has never been started — start it, then retry.

Both UIs are loopback-only, so open them through the tunnel from
[`04-ssh-access-model.md`](04-ssh-access-model.md):

- Prometheus: <http://127.0.0.1:9090> → *Status → Targets*. The
  `ratewatch-backend` target must be `UP`. `DOWN` with
  `context deadline exceeded` usually means the backend is not on the network
  (check `docker compose ps` in the main stack) or it is still starting; `DOWN`
  with a 404 is the missing-micrometer problem from §0.
- Grafana: <http://127.0.0.1:3000>, login `admin` / `GRAFANA_ADMIN_PASSWORD`
  (default `admin`; change it in the environment or on first login). The
  Prometheus datasource is auto-provisioned, so *Explore* works immediately.

## 2. The three questions you actually want answered

Don't build a wall of JVM dashboards. For RateWatch, three signals carry almost
all the information:

| Question | Signal | Where today |
|---|---|---|
| Is the app alive and serving? | `/api/actuator/health` | works now |
| Did the fetch succeed, and when did it last succeed? | a counter + a "seconds since last successful fetch" gauge you add in the scheduler/client | **you have to add it** |
| Is the cache helping or just failing silently? | cache hit/miss counters | after US-05 |

The second row is the interesting one and is worth designing before you write
it: a *timestamp of last success* is far more useful than a *count of
successes*, because "last success was 4 hours ago" is an alert, while "success
count = 137" is trivia. That gauge is also how you detect the nastiest failure
in this project — the scheduler silently not rescheduling itself after a
failure.

`management.endpoint.health.show-details: never` in `prod` is correct for a
public endpoint; if you need detail on the homelab, ask Health for it through
the tunnel or check the logs, don't flip that to `always` permanently.

## 3. Logs: what exists, what would come next

You already have structured JSON on the host at `/srv/ratewatch/logs`. That is
enough to answer most questions with `jq`:

```bash
jq -r 'select(.level=="ERROR") | [.timestamp, .logger_name, .message] | @tsv' \
  /srv/ratewatch/logs/app-json.log | tail -n 20
```

Shipping them to Loki/Grafana is a genuine step up, but it is also the part
most likely to eat a weekend, so treat it as a separate project with its own
AGENTS.md entry. Practical notes for when you do:

- **Do not add Promtail.** It is deprecated in favour of Grafana Alloy. Pick
  Alloy (or a plain Docker log driver) so you are not learning a dead tool.
- Alloy would mount `/srv/ratewatch/logs` read-only — the same bind mount the
  backend already writes to — so no change to the app is needed.
- Whatever you choose, the JSON fields to keep are `app_name`, `env`,
  `level`, `logger_name`, `traceId` (the MDC key is already included), and the
  `timestamp`. That field list is the reason `logback-spring.xml` has
  `customFields`.

## 4. Deliberately out of scope here

- Alertmanager / notifications. Add them only once you have the
  "last successful fetch age" gauge; alerting on absence of data is the point.
- Tracing (OpenTelemetry). Genuinely useful later — a scheduled fetch is
  exactly the kind of thing a trace clarifies — but it is not needed to answer
  "did it run".
- Retention tuning. 15 days is set in the compose command; a homelab with one
  target will not fill a disk at that setting.

Next: [`06-operations-runbook.md`](06-operations-runbook.md).
