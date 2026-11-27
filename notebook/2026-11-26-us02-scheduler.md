# US-02 — scheduling the next fetch

> Notebook entry. Format follows [`../07-notebook-template.md`](../07-notebook-template.md):
> English paragraph first, Vietnamese second, commands and identifiers never
> translated. `✍️` marks the parts only you can write — the facts around them are
> already filled in from the code as it stands today.

| | |
|---|---|
| Date | 2026-11-26 |
| Story | US-02 — schedule next fetch from `time_next_update_utc`, sane fallback |
| Status | implemented, one policy decision still open (see §4) |
| Shape chosen | (b) self-scheduling from the persisted `next_update_at` |

---

## 1. What the story actually asks

Fact, from `AGENTS.md` §5:

> US-02: schedule next fetch based on `time_next_update_utc`, with a sane
> fallback if that field is missing/invalid.

✍️ **EN:** In your own words, what does "sane fallback" have to protect against? Name at least two ways the timestamp can be unusable, not just "missing".
✍️ **VI:** Theo cách hiểu của bạn, "fallback hợp lý" phải bảo vệ điều gì? Kể ít nhất hai kiểu timestamp không dùng được, không chỉ "bị thiếu".

---

## 2. Why self-scheduling instead of `@Scheduled`

✍️ **EN:** Compare the two shapes you rejected: a fixed `fixedDelay` poll, and `@Scheduled(cron=…)`. What does each one fail to express about this provider?
✍️ **VI:** So sánh với hai cách bạn đã loại: poll `fixedDelay`, và `@Scheduled(cron=…)`. Mỗi cách không diễn tả được điều gì của provider này?

Facts worth keeping:

- `@Scheduled` cannot place a run at "the time the provider told me"; it expresses a grid.
- With a fixed poll, every wake-up costs one API call even when nothing changed.
- Self-scheduling means **each tick schedules its own successor** — which is also why an exception inside the tick can end the chain forever. Nothing in Spring protects you from that; see §4.

---

## 3. How one tick works today

```
ApplicationReadyEvent
  └── bootstrap()                       guard: app.schedule.enabled
        └── scheduleNextRun(clock.instant())
              └── tick()                runs on the "rate-sync-" thread
                    ├── fetchRateUseCase.execute()   -> SyncResult or throws
                    │     (SAVED / SKIPPED, or RateApiException, or
                    │      DataIntegrityViolationException on a lost race)
                    └── scheduleNext()  in a `finally`, always
                          ├── resolveNextDelay(now)
                          │     newest row -> next_update_at -> now+delay
                          │     null row / null column / DB error -> fallback
                          │     clamp to [1m .. 24h]
                          └── scheduleNextRun(nextRunAt)
```

Config, all in `application.yaml`:

| Key | Value | Guards against |
|---|---|---|
| `app.schedule.enabled` | `true` (env `RATE_SYNC_ENABLED`), `false` in tests | tests calling the real provider |
| `app.schedule.fallback-delay` | `1h` | `next_update_at` missing/invalid |
| `app.schedule.min-delay` | `1m` | a past timestamp becoming a busy loop |
| `app.schedule.max-delay` | `24h` | a nonsense future timestamp parking the scheduler |

Threading: one scheduler thread (`SchedulingConfig`), so an overlapping tick queues instead of racing the use case on the same `fetched_at`. `Clock` is UTC and injected, so delay arithmetic never depends on the host zone and tests can freeze time.

---

## 4. The open decision — failure path

✍️ **EN:** After a failed tick there is no new row, so `resolveNextDelay` reads the previous row whose timestamp is already past, and the clamp floors it at `minDelay`. Explain what that does in production, then decide: keep it, or use `fallbackDelay()` on failure. Write the answer you chose.
✍️ **VI:** Sau một tick thất bại không có dòng mới, nên `resolveNextDelay` đọc dòng cũ — mốc thời gian đã qua — và clamp kéo xuống `minDelay`. Giải thích điều đó nghĩa là gì khi chạy thật, rồi chọn: giữ nguyên hay dùng `fallbackDelay()` khi thất bại.

Fact: at `minDelay = 1m`, an outage retries ~60 times per hour. The provider is a free tier. This is the line where US-02 accidentally becomes US-03.

- [ ] Decision written here:
- [ ] `// TODO(you)` removed from `RateFetchScheduler.scheduleNext`

---

## 5. What the review caught

✍️ **EN:** For each row, write one sentence on whether you would have found it without the review, and how.
✍️ **VI:** Với mỗi dòng, một câu: nếu không review thì bạn có tự thấy không, và bằng cách nào?

| Finding | Why it mattered |
|---|---|
| `scheduledFuture` held but never read or cancelled | Dead state that suggests control the class does not have; also written from two threads |
| `taskScheduler.schedule` inside `finally` could throw `TaskRejectedException` | Exception from `finally` replaces the original failure, and the chain ends without a successor |
| `durationMs` measured before the DB read | The number meant "execute() time", not "tick time" |
| `resolveNextDelay` was `private` | Untestable without booting Spring; now package-private, so a fixed `Clock` + stubbed repository covers every branch |

---

## 6. Restart behaviour

✍️ **EN:** On boot the scheduler runs a tick immediately instead of waiting for `next_update_at`. What does that cost, and what does the dedup in the use case absorb? When would you change it?
✍️ **VI:** Khi khởi động, scheduler chạy tick ngay thay vì chờ `next_update_at`. Cái đó tốn gì, và dedup trong use case hấp thụ được gì? Khi nào bạn sẽ đổi?

Fact: in-memory "next run" is lost on every deploy, which is why the delay is always derived from the newest row rather than a field.

---

## 7. How to verify (on the homelab, or locally against the dev tunnel)

```bash
# 1. the delay logic, no network, no DB
./mvnw test -Dtest='RateHistoryTest,FetchRateUseCaseTest'

# 2. the loop itself
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
#    expect on boot:  rate_scheduler.started minDelay=PT1M maxDelay=PT24H fallbackDelay=PT1H
#                     rate_sync.saved ... durationMs=...
#                     rate_scheduler.tick outcome=SAVED ... nextRunAt=...

# 3. what is actually stored
docker compose exec postgres psql -U postgre -d ratewatch_db \
  -c 'select fetched_at, next_update_at, created_at from rate_history order by fetched_at desc limit 3;'
```

✍️ **EN:** Which of those three outputs would tell you the scheduler is broken but the fetch works? Which is the opposite?
✍️ **VI:** Trong ba output đó, cái nào cho biết scheduler hỏng nhưng fetch vẫn chạy? Cái nào ngược lại?

---

## 8. Glossary additions

| Term | EN definition (your words) | VI |
|---|---|---|
| self-scheduling | | |
| clamp (lower/upper bound) | | |
| fallback delay | | |
| `TaskScheduler` | | |
| injection of `Clock` for testability | | |
| restart-safety (state in DB, not memory) | | |

---

## 9. Self-test

1. Why can a `fixedDelay` poll cost more API calls than self-scheduling for the same 24 h period?
2. What happens if `next_update_at` is 3 days in the past, and which setting decides that?
3. Where exactly is the successor scheduled, and why in a `finally`?
4. Why does the scheduler inject `Clock` instead of calling `Instant.now()`?
5. If the tick succeeds but `rate_scheduler.tick` never appears in the log, what are the two candidate causes?

---

## 10. Links

- [`../../backend/src/main/java/com/ratewatch/backend/scheduler/RateFetchScheduler.java`](../backend/src/main/java/com/ratewatch/backend/scheduler/RateFetchScheduler.java)
- [`../../backend/src/main/java/com/ratewatch/backend/config/SchedulingConfig.java`](../backend/src/main/java/com/ratewatch/backend/config/SchedulingConfig.java)
- [`../../backend/src/main/java/com/ratewatch/backend/config/ScheduleProperties.java`](../backend/src/main/java/com/ratewatch/backend/config/ScheduleProperties.java)
- [`../../backend/src/main/resources/application.yaml`](../backend/src/main/resources/application.yaml)
- Next story: US-03 (retry with backoff) — where §4's decision belongs.
