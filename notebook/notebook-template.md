# Deployment notebook — RateWatch homelab (bilingual template)

> **How to use this file.** Copy it, rename to `notebook/YYYY-MM-DD-deployment.md`,
> and fill in your own prose. Prompts are marked `✍️`; delete each prompt once the
> paragraph under it exists. The `must contain` checklists are acceptance
> criteria for *you* — they describe what a reader (future-you) needs, not
> wording to copy.
>
> Nothing in this template is content. If a section reads like it is already
> written, that is a bug in the template, not a shortcut.

## Format rules (decide once, then never relitigate mid-draft)

1. **English first, Vietnamese second, per section.** Do not interleave
   sentence-by-sentence. One English block, one Vietnamese block. This keeps
   each language readable and makes drift obvious.
2. **Never translate:** commands, file paths, YAML keys, Spring property names,
   container/service names, metric names, HTTP paths, log field names. Both
   languages carry them verbatim.
3. **One translation per term.** Pick the Vietnamese rendering on first use,
   record it in the glossary (§12), and use only that afterwards.
4. **Facts beat adjectives.** Every claim should be something a command could
   disprove. "Redis is disposable" is weak; "Redis has no volume, so
   `docker compose restart redis` can only cost a cache miss" is a fact.
5. **Commands show their machine.** Prefix every command block with where it
   runs: *on the host*, *inside the container*, *from CI*. Your access model has
   three distinct vantage points, so this is not pedantry.

---

## 0. Snapshot / Tổng quan tại thời điểm viết

✍️ EN: What was true on the day you wrote this — host, OS, Docker/Compose
versions, image tags, commit SHA, and how you verified each.
✍️ VI: Ngày viết ghi chú này, máy chủ và phiên bản Docker/Compose/image tag
đang là gì, và bạn đã kiểm tra bằng lệnh nào?

Must contain: a version table with a "verified by" column; the date; the repo
commit deployed.

| Item | Value | Verified by |
|---|---|---|
| | | |

---

## 1. Goal & scope / Mục tiêu & phạm vi

✍️ EN: In two sentences, what is this deployment *for* (map it to US-01..08),
and what does it deliberately not do?
✍️ VI: Hai câu: hệ thống này phục vụ việc gì, và cố tình KHÔNG làm gì?

✍️ EN: Why is "no user accounts, no auth in the app" a safe choice *given* your
access layer? Name the component that enforces access, and the day that
assumption would break.
✍️ VI: Vì sao "không có auth trong app" là chấp nhận được với mô hình truy cập
hiện tại? Thành phần nào đang gánh việc xác thực? Giả định đó sẽ sai vào lúc nào?

Must contain: the trust boundary written down explicitly; a sentence that would
let a reader say "so if X changes, we must add auth".

---

## 2. Host & deploy user / Máy chủ & tài khoản triển khai

✍️ EN: Describe the deploy user, its group membership, and what root-equivalent
power that membership grants. Then: what is the smallest thing that would reduce
that power?
✍️ VI: Tài khoản deploy là gì, thuộc nhóm nào, và quyền đó tương đương root ở
điểm nào? Muốn giảm quyền đó thì làm gì?

✍️ EN: Where does each kind of state live on disk (compose files, `.env`,
volumes, logs, backups), and which of them is allowed to be lost?
✍️ VI: Từng loại dữ liệu nằm ở đâu trên đĩa (compose, `.env`, volume, log,
backup), và loại nào được phép mất?

Must contain: a directory table with a "backed up? / regenerable?" column; the
`.env` file's permissions; where the deploy user's SSH key lives and which key
CI uses.

---

## 3. Network & access model / Mô hình mạng & truy cập

✍️ EN: Draw or describe the path from "browser request" to "Spring controller",
naming every hop and where TLS terminates.
✍️ VI: Mô tả đường đi của một request từ trình duyệt tới controller, kể tên từng
chặng và TLS kết thúc ở đâu.

✍️ EN: Explain why **zero published ports** is a different guarantee from
`127.0.0.1:5432:5432`. What class of mistake does it remove?
✍️ VI: Vì sao "không publish port nào" khác về bản chất so với bind vào
`127.0.0.1`? Nó loại bỏ được loại lỗi nào?

✍️ EN: Prove service-name resolution with the command you actually ran, and
state where DNS comes from.
✍️ VI: Chứng minh việc phân giải tên service bằng đúng lệnh bạn đã chạy, và DNS
đến từ đâu.

✍️ EN: Tailscale is limited to CI/CD and remote SSH. What does that leave as the
only inbound paths, and what would you check first if the public URL stopped
resolving but containers were healthy?
✍️ VI: Tailscale chỉ dùng cho CI/CD và SSH từ xa. Vậy còn lại những đường vào
nào? Nếu URL công khai không truy cập được nhưng container vẫn khỏe, bạn kiểm tra
gì trước?

Must contain: the ingress/egress direction of cloudflared (it dials out — no
inbound port); the DNS name each service uses; the exact `curl`/`exec` command
that proves it; a sentence on who can reach actuator endpoints now that they are
behind the tunnel.

---

## 4. Data services / Dịch vụ dữ liệu

✍️ EN: For Postgres: what makes it the source of truth, what is persisted, and
what exactly happens the first time the volume is empty versus the second time?
✍️ VI: Với Postgres: điều gì khiến nó là source of truth, dữ liệu nào được lưu,
và lần đầu volume rỗng khác gì lần thứ hai?

✍️ EN: For Redis: what is *not* persisted, why that is intentional, and which
two commands would show you it came back empty?
✍️ VI: Với Redis: cái gì KHÔNG được lưu, vì sao đó là chủ ý, và hai lệnh nào cho
thấy nó quay lại trạng thái rỗng?

✍️ EN: Describe the healthcheck → readiness gap in one sentence, then say which
single authenticated command you run when you do not believe the green tick.
✍️ VI: Mô tả khoảng cách giữa healthcheck và "thực sự sẵn sàng" trong một câu,
rồi nêu lệnh có xác thực mà bạn chạy khi không tin vào dấu tick xanh.

Must contain: the initialisation trap (env vars only apply to an empty volume);
volume name; why Redis has none; the authenticated verification command for
each service.

---

## 5. Backend service / Dịch vụ backend

✍️ EN: List the environment variables that actually change behaviour, and for
each one say which Spring property consumes it.
✍️ VI: Liệt kê các biến môi trường thực sự thay đổi hành vi, và mỗi biến được
Spring property nào đọc.

✍️ EN: Why is `DB_HOST` a service name here and `localhost` there? What breaks
if you swap them?
✍️ VI: Vì sao `DB_HOST` chỗ này là tên service, chỗ kia là `localhost`? Đổi chỗ
thì hỏng cái gì?

✍️ EN: Where do logs go, and what is the difference between what
`docker compose logs` shows and what lands on the host?
✍️ VI: Log đi đâu, và log của `docker compose logs` khác gì log ghi ra host?

✍️ EN: What does `SIGTERM` do to the app today, and what does that mean for an
in-flight scheduled fetch?
✍️ VI: `SIGTERM` hiện tại gây ra điều gì, và điều đó ảnh hưởng gì tới một lần
fetch đang chạy dở?

Must contain: the env→property table; the context-path consequence (`/api`
prefixes actuator too); the log volume path; one honest sentence about shutdown
behaviour that you are not happy with yet.

---

## 6. Observability / Giám sát & quan sát

✍️ EN: Which two observability signals exist *today* without any new code, and
which one is missing?
✍️ VI: Hai tín hiệu quan sát nào đang có sẵn mà không cần viết thêm code, và
tín hiệu nào còn thiếu?

✍️ EN: Explain what is wrong with "count of successful fetches" as an alert, and
what you would measure instead.
✍️ VI: Vì sao "số lần fetch thành công" là chỉ số tồi để cảnh báo, và bạn sẽ đo
gì thay thế?

✍️ EN: Now that there are no published ports, how do you reach a metrics UI? Name
the tradeoff you accepted.
✍️ VI: Khi không còn port nào publish, làm sao mở được UI metrics? Bạn chấp nhận
đánh đổi gì?

Must contain: the missing `micrometer-registry-prometheus` fact (404, not a
warning); the "time since last success" gauge idea; how the metrics UI is
reachable, or an explicit "not reachable yet, and that is accepted because…".

---

## 7. Deploy & release / Quy trình triển khai & phát hành

✍️ EN: Walk one commit from `git push` to running container, naming the identity
and network used at each step.
✍️ VI: Kể lại một commit đi từ `git push` tới container đang chạy, nêu rõ danh
tính và mạng được dùng ở từng bước.

✍️ EN: How do you roll back, and what must still exist on disk for that to work?
✍️ VI: Rollback bằng cách nào, và trên đĩa phải còn gì thì rollback mới dùng được?

✍️ EN: Which command would delete your history if you ran it, and what is the
safer instinct?
✍️ VI: Lệnh nào sẽ xoá sạch lịch sử nếu lỡ chạy, và phản xạ an toàn hơn là gì?

Must contain: image tagging scheme; the `down -v` warning in your own words;
where CI's credentials live and how they are scoped.

---

## 8. Operations & failure modes / Vận hành & các chế độ lỗi

✍️ EN: For each of these, write the *first* command you would run and what its
output would tell you: container not running · app healthy but no new rows ·
public URL dead but containers healthy · host slow.
✍️ VI: Với mỗi tình huống, ghi lệnh ĐẦU TIÊN bạn sẽ chạy và output của nó cho
biết điều gì: container không chạy · app khỏe nhưng không có dòng mới · URL công
khai chết nhưng container khỏe · máy chủ chậm.

✍️ EN: Describe the backup, then describe the restore drill — including the one
number you compare afterwards.
✍️ VI: Mô tả backup, rồi mô tả lần diễn tập restore — kèm con số bạn so sánh sau
khi restore.

Must contain: symptom→command→expected-output triples; backup location *and*
where the copy goes off-host; secret rotation order; the fact that a Postgres
major upgrade is not a tag change.

---

## 9. Deviations from guides 01–06 / Khác biệt so với hướng dẫn 01–06

This is the most valuable section for future-you: the guides describe a
loopback-bound model, your implementation went further.

✍️ EN: For each row, state what the guide assumed, what you built, and why.
Where the guide is now *wrong* for you, say so plainly.
✍️ VI: Với mỗi dòng, ghi hướng dẫn giả định gì, bạn làm gì, và vì sao. Chỗ nào
hướng dẫn không còn đúng với bạn thì nói thẳng.

| Topic | Guide assumption | Your implementation | Why |
|---|---|---|---|
| Port exposure | `127.0.0.1:` prefix + SSH `-L` | No published ports at all | |
| Ingress | Tunnel to loopback | cloudflared dials out, Cloudflare terminates TLS | |
| CI access | Manual `git pull` on host | Tailscale-scoped CI identity | |
| Host verification | `curl 127.0.0.1:8081/...` | must run inside the network | |
| Monitoring UI | loopback + tunnel | ? | |

Must contain: the commands from guides 02–05 that you can no longer copy-paste,
each paired with its replacement.

---

## 10. What surprised me / Điều gì làm tôi bất ngờ

✍️ EN: Three things, each phrased as "I expected X, I got Y". One must be a
mistake you made.
✍️ VI: Ba điều, mỗi điều viết dạng "tôi tưởng X, thực tế Y". Ít nhất một điều là
lỗi của chính bạn.

✍️ EN: Which assumption from the guides turned out to be load-bearing, and how
would it have failed at 2 a.m.?
✍️ VI: Giả định nào trong tài liệu hoá ra là quan trọng bậc nhất, và nó sẽ hỏng
như thế nào lúc 2 giờ sáng?

---

## 11. Open questions & next steps / Câu hỏi mở & bước tiếp theo

✍️ EN: Carry forward the unresolved decisions (Supabase transport, DB port
strategy, alerting, log shipping). For each: the options, and what evidence
would settle it.
✍️ VI: Chuyển tiếp các quyết định còn mở (cách gửi Supabase, chiến lược port DB,
alerting, gửi log). Với mỗi cái: các lựa chọn, và cần bằng chứng gì để chốt.

Must contain: a link into AGENTS.md §8 so this does not become a second,
diverging decision list.

---

## 12. Glossary & self-test / Thuật ngữ & tự kiểm tra

### Glossary — fill definition columns only after you have used the term above

| Term | EN definition (your words) | VI | Where it appears |
|---|---|---|---|
| published port | | | |
| port binding / host loopback | | | |
| isolated Docker network | | | |
| service discovery (DNS name) | | | |
| reverse proxy / ingress | | | |
| outbound tunnel | | | |
| named volume | | | |
| healthcheck | | | |
| readiness vs liveness | | | |
| cache eviction | | | |
| TTL | | | |
| source of truth | | | |
| graceful shutdown | | | |
| backup vs restore drill | | | |
| secret rotation | | | |
| least privilege | | | |

### Self-test — answer from memory, then check the notebook

1. What exactly does `(healthy)` guarantee for Postgres, and what does it not?
2. Which env vars are consumed only on an empty volume, and what is the symptom
   when they drift?
3. Why does `localhost` inside the backend container never mean the database?
4. Sketch the request path browser → controller, naming the TLS terminator.
5. Why can no port on the host be probed with `nmap`?
6. Which single command deletes the rate history, and why is it tempting?
7. What is missing for `/api/actuator/prometheus` to return 200?
8. What does a cold Redis cost you, and what does a cold Postgres cost you?
9. Where is the first exception you read in a Boot log, first or last?
10. If the public URL is dead but `docker compose ps` is green, what are the two
    most likely causes?
