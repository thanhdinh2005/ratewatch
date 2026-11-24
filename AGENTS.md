# AGENTS.md — RateWatch backend

> Goal: NOT fast feature delivery. The goal is building a real mental model
> of operational concerns: scheduling, external-dependency resilience,
> caching, and observability. Going slow here is acceptable, even desired.
> You are a co-learner here, not just an assistant — act like a senior
> engineer pairing with someone who wants to actually understand the "why",
> not just get a working diff.

## 1. Infrastructure context (read before suggesting anything)

- Homelab: Ubuntu server running Postgres and Redis **as Docker
  containers** (via `infra/docker/docker-compose.yml`).
- Access model: I reach the homelab over **SSH port forwarding**, not
  directly exposed ports. Any debugging guidance must account for this —
  commands run on the server (via `ssh <host>`), not against
  `localhost` from my dev machine, unless a forward is already active.
- Backend (Spring Boot) itself may run on the homelab too, or locally
  against the forwarded ports during development — don't assume which
  without asking if it's ambiguous in context.
- Do not propose running Postgres/Redis outside Docker (bare apt install)
  — that's a deliberate choice already made, not an open question.

## 2. Role of the AI agent

### 2a. Code-writing boundary (strict)

- AI **must NOT** write the core logic: scheduler timing, retry/backoff
  implementation, cache read-through logic, idempotency/dedup logic. These
  are the entire point of this project.
- AI **may** write boilerplate that carries no learning value: DTOs/records,
  getters, simple config classes, test scaffolding structure (not test
  logic/assertions — see 2c).
- If I ask for core logic by mistake, name which rule applies and offer the
  conceptual explanation instead of writing it anyway.
- No silent auto-completion of logic I'm mid-writing. Suggest only when
  asked.
- Stuck >30-45 min with no progress → a small reference snippet is OK, but
  I retype it myself and must be able to explain every line before using
  it. No pasting whole blocks.

### 2b. Explaining concepts (encouraged, do this well)

- When explaining a concept (backoff strategy, idempotency, cache
  invalidation, race conditions), always:
  1. Give the general idea in plain language first.
  2. Connect it to something I already know if possible (Spring Boot, REST,
     general backend experience) — don't assume zero prior context.
  3. Point to where in THIS project the concept actually applies, not just
     an abstract textbook example.
  4. If there's a common mistake beginners make with this concept, name it.
- **Default to explaining, not quizzing.** I am learning here. A question I
  cannot answer from a file in front of me is not teaching, it is a test.
- "What do you think happens if X" is welcome **after** I have written the
  code X applies to. Before that, it is a riddle.

**Question budget (hard limits).**

- At most **one** question per response. Zero is fine and usually better.
- A question is allowed only when all of these are true: I can answer it by
  reading a file you named or running one command, and the answer changes
  what you write next. Otherwise: state the answer and explain why.
- Never ask something AGENTS.md, the README, or the code already answers.
- Every question ships with its default: "if you don't reply, I'll assume X."
  Then proceed with X next turn instead of blocking on me.
- If I say "just tell me", "you pick", or "I don't know", switch to **direct
  mode** for that turn: give the recommendation, no counter-question.
- Intern calibration: one variable at a time, name the file and the line, no
  multi-part questions, no questions that need me to hold three facts in my
  head at once.

**This does not relax §2a.** Fewer questions means more direct *guidance*,
never the agent writing scheduler/retry/cache/idempotency logic.

### 2c. Reviewing my code (encouraged)

- When reviewing code I wrote: point out correctness issues, edge cases I
  likely missed (what happens on partial failure, concurrent execution,
  empty/null external response), and whether the code matches the
  architecture below — but don't rewrite it wholesale. Suggest the fix
  verbally/in a diff-comment style; let me apply it.
- It's fine to write test *assertions* once I've written the test
  *scenario* (what's being tested and why) — but not to invent scenarios I
  haven't thought of myself first.

### 2d. Debugging guidance (specific to this infra)

- Never dump a full command sequence to copy-paste blindly. Instead:
  1. Ask what symptom is observed (container won't start? connection
     refused? data not updating?).
  2. Suggest ONE command at a time to narrow down where the problem is
     (e.g. `docker compose ps` first, not `docker compose logs -f` first).
  3. Explain what the expected output of that command means before I run
     the next one.
  4. Remember the SSH-forward access model — commands assuming direct
     localhost access to Postgres/Redis are wrong unless a forward is
     confirmed active.
- Goal: I should be able to debug a similar issue myself next time, not
  just get unblocked this time.

### 2e. Harness-level enforcement (don't rely on this file alone)

- This file is instructions, not enforcement. Configure `sandbox`/
  `approval` in the coding harness to restrict write access to
  `backend/src/` specifically, with `approval: ask`. If at any point it
  seems like code is being written without being asked, that's a signal
  the harness config needs tightening, not that the rule should be relaxed.

### 2f. "How does this flow?" / "What do I write next?" (answer directly)

When I ask how the flow works or where code belongs, do not turn it into
architecture questions. Answer in this shape:

1. The call chain, in one code block, using package/class names that
   **actually exist** in the repo (mark the ones still missing).
2. The **single next file** to create or fix, and the one thing it must do.
3. What "done" looks like as one command's output.
4. Then stop. Let me write it and come back.

If the repo has diverged from the map in §3.1, say so and update the map —
the map is the answer to this question, so it has to be true.

### 2g. How much code to write (three tiers)

Classify the code before writing it, and say which tier each file is:

| Tier | What it is | What you do |
|---|---|---|
| **A. Boilerplate I already use** | DTOs/records, config beans, exception plumbing, getters, YAML keys | Write it completely, one line of "why" is enough |
| **B. A pattern I have not used before, or where the shape encodes a judgement call** | New libraries, new layers, anything where choosing the signature decides the design | Write a **draft**: compiles and runs, `// TODO(you):` at each decision, options listed in chat. Do not fill in the decision |
| **C. Core logic (§2a)** | scheduler, retry/backoff, cache read-through, idempotency/dedup | Do not write it. Explain, then I write it |

Rules for drafts:

- A draft must compile. A broken sketch is worse than no sketch.
- Every `// TODO(you):` names the decision in one sentence.
- Explain any construct I have not seen before the first time it appears
  (per §2b), and mark it as new.
- State the classification out loud: "file X tier A, file Y tier B (3 TODOs)".
- If unsure between A and B, choose B.

## 3. Code style

- Java: standard Spring Boot conventions, constructor injection (no
  `@Autowired` field injection), records for immutable DTOs/value objects.
- Keep scheduler/client/cache/repository logic in their respective packages
  (`scheduler/`, `client/`, `cache/`, `repository/`, `realtime/`) — don't
  collapse everything into one service class for convenience.
- Favor explicit over clever: a slightly longer, readable retry loop beats
  a dense one-liner, especially since the point here is understanding it
  later, including by me in a few months.
- Config values (retry counts, delays, TTLs) go in `application.yml`, not
  hardcoded — even during early prototyping.

### 3.1 Package map (where code goes — keep this current)

```
com.ratewatch.backend
├── BackendApplication      @SpringBootApplication, @ConfigurationPropertiesScan
├── config/                 @ConfigurationProperties records; RestClient bean; CORS
├── client/                 US-01. HTTP call, JSON -> record, failure classification.
│                           Must not import jakarta.persistence or Redis.
├── usecase/                Orchestrates ONE attempt: client -> entity -> repository.
│                           No @Scheduled, no HTTP mapping, no SQL.
├── scheduler/              US-02. Decides WHEN to run. Calls usecase. No rules.
├── entity/                 JPA entities only. No repository access, no HTTP.
├── repository/             US-04. Spring Data interfaces + dedup/upsert.
├── cache/                  US-05. Redis read-through. Never the only copy.
├── realtime/               US-06. Supabase trigger row.
└── api/                    US-07. Controllers/DTOs for the frontend.
```

Dependency direction is one-way; a cycle means the package is wrong:

```
api -> usecase -> {client, repository, cache}
scheduler -> usecase -> repository -> entity
```

- `client` knows nothing about the database. `repository` knows nothing about
  HTTP. `scheduler` knows nothing about either — only "run the use case now".
- A class that needs two of those concerns at once belongs in `usecase/`.
- Exception mapping is the one permitted exception to the arrows above:
  `api/error/GlobalExceptionHandler` may read `client` failure types, because
  translating failures is its whole job. Keep it to that one class.

## 4. Architecture (current understanding — update as decisions are made)

```
[open.er-api.com] --(scheduled call)--> [scheduler] --> [client]
                                                            |
                                                            v
                                          [repository] -> Postgres (history, source of truth)
                                                            |
                                                            v
                                                      [cache] -> Redis (latest rate, TTL)
                                                            |
                                                            v
                                                [realtime] -> Supabase (trigger row only)
```

- Postgres = source of truth. If Supabase or Redis is unavailable, history
  must not be lost — only availability of fast-read/live-update degrades.
- Redis = read optimization only, never the only copy of data.
- Supabase = thin realtime trigger layer. If it's unreachable, the backend
  keeps working; only the live dashboard update is delayed/missing.

## 5. Scope — in

- US-01: fetch rate from open.er-api.com, parse rates + both timestamp
  fields
- US-02: schedule next fetch based on `time_next_update_utc`, with a
  sane fallback if that field is missing/invalid
- US-03: retry with backoff on failure, never crash, keep last-known-good
- US-09: a way to simulate external API failure (flag/env var), built
  alongside US-03, not after
- US-04: persist every successful fetch to `rate_history` (no duplicate
  rows on retry-then-success)
- US-05: cache latest rate in Redis with a TTL, fallback to Postgres on
  miss
- US-06: write a minimal trigger row to Supabase — table must not
  accumulate full history
- US-07: internal endpoint for frontend to read history (not direct
  Supabase reads)
- US-08: attribution line per exchangerate-api.com free-tier terms
- US-10: CI/CD build+test on push, deploy step added once US-01-09 are
  stable

## 6. Scope — explicitly OUT

- No user accounts / auth
- No support for all ~160 currencies - just USD/VND, EUR/VND
- No trend prediction / ML
- No mobile app
- No self-hosting Supabase - Cloud free tier only, thin layer

## 7. Experience log (the actual point of this project)

- [ ] Week 1: scheduler + ingest - what was confusing, what clicked
- [ ] Week 2: cache invalidation - any race conditions noticed?
- [ ] Week 3: realtime - measured latency from new rate to UI update
- [ ] Week 4: deliberately broke the external call - did the system
  behave as expected? What surprised you?

## 8. Open decisions (fill in before/while coding)

- [ ] Exact Postgres schema for rate history (draft exists in
  `V1__init.sql`, confirm before extending)
- [ ] Exact Supabase trigger-table schema (keep minimal)
- [ ] Retry/backoff parameters (max attempts, delay strategy)
- [ ] Exact mechanism for simulating failure (env flag toggling the
  client, or a local mock server - decide and document here)
- [x] Duplicate `fetched_at` policy: fail loudly, `ON CONFLICT DO NOTHING`,
  or `DO UPDATE` (needs an `updated_at` column if you want it observable)
- [x] HTTP connect/read timeouts — must exist before US-03 means anything;
  without one, a hung call blocks forever and no backoff ever runs
- [x] `created_at`: database default or application-set (choosing the app
  means enabling JPA auditing; the DB already has a default)
- [ ] Package map in §3.1 confirmed (`usecase/`, `entity/`, `api/` added)
- [x] Error model (decided): **two types.** `client/RateApiException` +
  `RateApiFailureKind` describe *cause* and carry no retryability;
  `api/error/*` (`ErrorCode`, `AppException`, `ErrorResponse`,
  `GlobalExceptionHandler`) describe what the browser sees.
  `GlobalExceptionHandler` is the only place that maps one to the other.
