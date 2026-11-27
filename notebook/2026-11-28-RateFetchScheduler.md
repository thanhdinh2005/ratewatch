# RateFetchScheduler — ghi chú cơ chế

## 1. Self-rescheduling, không phải `@Scheduled(fixedRate=...)`

Không có `@EnableScheduling`. Mỗi `tick()` tự đăng ký tick kế tiếp qua
`TaskScheduler` — lý do duy nhất để làm vậy: cho phép lần chạy kế tiếp đặt
đúng vào `next_update_at` lấy từ DB, thay vì lưới thời gian cố định.

Hai invariant bắt buộc:
- Mọi tick, kể cả fail, phải kết thúc bằng một lần schedule kế tiếp — nếu
  không, chuỗi chết âm thầm trong khi app vẫn "khoẻ".
- Delay luôn bị `clamp` trong `[minDelay, maxDelay]` — tránh busy-loop (quá
  khứ) và tránh "đóng băng" nhiều năm (tương lai phi lý).

## 2. Reference vs runtime — khác nhau ở *ai* chủ động

- Giữ `ScheduledFuture` trong field → có "vé" để `.cancel()` bất cứ lúc nào
  (chủ động, static, đọc code biết ngay).
- Không giữ reference (bản hiện tại) → không có cách huỷ chủ động. Chuỗi chỉ
  dừng khi `TaskScheduler`'s executor tự reject do đã shutdown (bị động,
  runtime, phụ thuộc lifecycle).
- Đừng lẫn hai cơ chế này — một cái là compile-time có/không có handle, một
  cái là hành vi runtime của executor.

## 3. `Optional.map()` short-circuit, không phải try/catch

`orElseGet` không bắt exception hay null phát sinh giữa chuỗi `.map()`.
Cơ chế thật: `map()` chỉ gọi hàm mapper nếu Optional đang *present*; nếu giá
trị là `null`, `Optional.ofNullable(null)` biến nó thành `empty()` ngay, các
`.map()` sau đó tự skip (không gọi lambda, không throw). Chuỗi "rơi" xuống
`orElseGet` một cách êm xuôi, không phải vì "bắt được lỗi".

Hệ quả cần nhớ: "row không tồn tại" và "row tồn tại nhưng field null" đi theo
hai nhánh input khác nhau nhưng **hội tụ cùng kết quả** (`fallbackDelay()`) —
mất khả năng phân biệt hai tình huống rất khác nhau (cold start vs data lỗi).

## 4. Hai try/catch khác method không chia sẻ nhau

`log.error("fetch_failed")` nằm trong catch của `tick()` (bắt lỗi từ
`execute()`). Lỗi trong `resolveNextDelay()` (query DB) nằm trong catch
*khác*, ở method khác. Không được suy luận "nhánh A không lỗi → nhánh B
cũng im" nếu hai nhánh không nằm chung một try/catch.

## 5. `try/finally` tự nó là cơ chế loại trừ overlap

`scheduleNext()` (nơi đăng ký tick kế tiếp) nằm trong `finally` của `tick()`.
Theo ngữ nghĩa Java, `finally` chỉ chạy sau khi `try` đã kết thúc hoàn toàn.
Vậy nếu `execute()` đang "treo" (chưa return), `finally` chưa từng được
chạy tới → tick kế tiếp **chưa được đăng ký**, không thể tồn tại để overlap
với tick đang treo. Đây là mutual exclusion tự nhiên từ cấu trúc code, không
cần `@Transactional` hay lock tường minh — khác hẳn `@Scheduled(fixedRate)`
vốn có thể overlap nếu không cấu hình cẩn thận.

## 6. Một lần reject = chết vĩnh viễn, không tự hồi phục

`scheduleNextRun` bắt `TaskRejectedException` chỉ để log warn, không ném lại,
không retry. Mỗi tick chỉ có đúng **một cơ hội** gọi lại `scheduleNextRun`.
Nếu cơ hội đó bị reject (ví dụ do queue đầy, không phải do shutdown), không
có tick nào trong tương lai được đăng ký nữa — kể cả khi điều kiện gây reject
sau đó tự hết. Chuỗi domino: domino nào không dựng được domino sau, chuỗi
dừng tại đó mãi mãi. App vẫn chạy bình thường ở mọi mặt khác — chỉ riêng việc
fetch rate chết im lặng, không ai biết trừ khi có alert riêng theo dõi.

## Bài học tổng (lỗi lặp lại trong buổi học)

Ba lần trả lời sai đều không phải thiếu kiến thức Java cơ bản, mà do **lẫn
hai cơ chế khác nhau thành một**:
- Reference (compile-time, static) vs hành vi executor (runtime).
- Try/catch ở method A vs try/catch ở method B — tưởng chúng dùng chung.
- Mệnh đề mâu thuẫn trong chính giả thiết ("treo 10 phút" và "return trong
  10 phút" không thể cùng đúng).

Cách luyện: trước khi kết luận về một đoạn code, tự hỏi cho từng bước:
1. Bước này đọc/ghi field nào?
2. Bước này *bắt buộc* chạy sau bước nào — do cú pháp ngôn ngữ, do field
   dùng chung, hay do gọi trực tiếp? (không phải "do nó nằm ở dòng dưới")

Nếu không trả lời được 2 câu đó cho một cặp method, đừng kết luận chúng
"liên quan" hay "cô lập" theo cảm giác.