# 01 — Host prerequisites (Ubuntu)

Goal: the Ubuntu box is ready to run containers safely and you can prove it
before you debug anything else. Everything here is one-time work.

Assumed: Ubuntu 22.04/24.04, sudo access, SSH working, repo cloned at
`~/ratewatch`.

---

## 1. Install the Docker Engine from Docker's own repository

Not `apt install docker.io` from the Ubuntu archive: that package lags behind,
and more importantly your Compose v2 and engine versions drift from every
tutorial you will read. The canonical install script is fine for a homelab:

```bash
curl -fsSL https://get.docker.com | sudo sh
```

Why this is acceptable here: it installs `docker-ce`, `docker-ce-cli`,
`containerd.io`, `docker-buildx-plugin`, `docker-compose-plugin` from
`download.docker.com`, and keeps you on `apt` for upgrades.

Confirm the compose *plugin* is present — this is the thing that is most often
missing on older setups:

```bash
docker compose version
```

Expected: `Docker Compose version v2.x.y`. If you instead see
`docker: 'compose' is not a docker command`, the plugin is missing; install
`docker-compose-plugin` rather than reaching for the legacy `docker-compose`
Python tool (v1 has different `.env` interpolation behaviour and will waste
your afternoon).

```bash
docker version
```

Expected: a `Client:` block *and* a `Server:` block with the same-ish version.
If `Server:` errors with "Cannot connect to the Docker daemon", the daemon is
not running (`sudo systemctl status docker`) — stop here and fix that, do not
look at compose files yet.

## 2. Run docker without sudo

```bash
sudo usermod -aG docker "$USER"
newgrp docker
```

`newgrp` matters: group membership only applies to *new* login shells, so
without it the current SSH session still needs `sudo`. Verify:

```bash
docker ps
```

Expected: a header row (`CONTAINER ID IMAGE ...`) and no permission error.

Security note, since you asked for the tradeoff to be visible: membership in
`docker` is effectively root on the host (you can mount `/` into a container).
That is acceptable on a single-user homelab; it is not acceptable on a shared
box.

## 3. Create the directory layout

One host directory tree, so a `find` or a `du` later has an obvious target and
so log/backup paths never depend on where you happened to clone things.

```bash
sudo mkdir -p /srv/ratewatch/{logs,backups}
sudo chown -R "$USER":"$USER" /srv/ratewatch
```

| Path | Contents | Backed up? |
|---|---|---|
| `/srv/ratewatch/logs` | bind-mounted to `/app/logs` (logback JSON files) | no — regenerable |
| `/srv/ratewatch/backups` | `pg_dump` output, see runbook | yes, off-host ideally |
| docker volume `ratewatch_pgdata` | Postgres cluster | **yes — this is the data** |

The bind mount is why `LOG_DIR` in `compose/.env` defaults to
`/srv/ratewatch/logs`. If the directory does not exist, Docker creates it as
`root:root` and a non-root container user may then fail to write — creating it
yourself avoids that class of bug.

## 4. Firewall: expose SSH, nothing else

Because every published port in the compose file is prefixed with `127.0.0.1`,
Docker's iptables rules never open those ports to the LAN. So the only inbound
rule you need is SSH.

```bash
sudo ufw status verbose
```

Expected after setup: `Status: active`, default `deny (incoming)`, and exactly
one `ALLOW` for `22/tcp` (or your custom SSH port). If you see 5432/6379/8081
listed, something else on the host is publishing those ports — find it with
`sudo ss -ltnp` before assuming the LAN is safe.

Caution: Docker publishes ports by inserting rules that can bypass ufw. The
`127.0.0.1:` prefix is what actually protects you, not ufw. This is the reason
we bind explicitly rather than relying on the firewall.

## 5. Time and timezone

The whole project hinges on two timestamps from an external API
(`time_last_update_utc`, `time_next_update_utc`) and a scheduler that reasons
about them. A drifting clock turns "why didn't it fetch?" into a mystery.

```bash
timedatectl
```

Expected: `System clock synchronized: yes`, `NTP service: active`, and a
timezone consistent with the `TZ` you put in `compose/.env`. Do not mix: keep
the *host* in your local zone, keep the *database* storing `timestamptz`, and
let the app parse the API's UTC strings explicitly. Confusion in this area is
almost always a "we compared a local time to a UTC time" bug, not a container
bug.

## 6. Docker daemon hygiene (do this before you generate logs for weeks)

Container logs are JSON files under `/var/lib/docker` and grow forever by
default. `logback-spring.xml` already caps *its* files, but not the engine's.

Check current usage:

```bash
docker system df
```

Then create `/etc/docker/daemon.json` if absent:

```json
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "5" }
}
```

```bash
sudo systemctl restart docker
```

Expected: `docker system df` still lists images/volumes/containers, and
`docker info --format '{{.LoggingDriver}}'` prints `json-file`. The caps apply
to containers *created after* the restart.

## 7. Resource sanity

```bash
free -h
df -h /srv /var/lib/docker
nproc
```

What you are checking: the JVM will take `MaxRAMPercentage=70` of the
*container* limit (unlimited by default, so effectively of host RAM), Postgres
wants a few hundred MB of shared buffers, and Prometheus retention costs disk.
On a 4 GB / 2 vCPU box you should set explicit `mem_limit`s instead of trusting
a 70% default; on 8 GB+ the defaults here are fine.

Optional but recommended on a small box — cap the backend so a leak cannot take
Postgres down with it. Add to the `backend` service in
`compose/docker-compose.yml`:

```yaml
    mem_limit: 1g
```

Restart it and confirm the JVM honoured it:

```bash
docker compose exec backend java -XX:+PrintFlagsFinal -version | grep -i maxheapsize
```

## 8. Prerequisite checklist

- [ ] `docker compose version` prints v2.x
- [ ] `docker ps` works without sudo in the current shell
- [ ] `/srv/ratewatch/{logs,backups}` exist and are owned by you
- [ ] `ufw` allows only SSH inbound
- [ ] `timedatectl` shows NTP synchronized
- [ ] `/etc/docker/daemon.json` caps container log size
- [ ] You know how much RAM/disk the host has (you wrote it down somewhere)

Next: [`02-compose-data-services.md`](02-compose-data-services.md).
