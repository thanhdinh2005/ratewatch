package com.ratewatch.backend.scheduler;

import com.ratewatch.backend.config.ScheduleProperties;
import com.ratewatch.backend.entity.RateHistory;
import com.ratewatch.backend.repository.RateHistoryRepository;
import com.ratewatch.backend.usecase.FetchRateUseCase;
import com.ratewatch.backend.usecase.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The US-02 loop: wake up, run one sync, decide when to wake up again.
 *
 * <p>Shape: self-scheduling. There is no {@code @Scheduled} method and no
 * {@code @EnableScheduling}; each tick schedules its own successor through the
 * {@link TaskScheduler} bean. That is the only way the next run can be placed
 * at the provider's {@code next_update_at} instead of on a fixed grid.
 *
 * <p>Two invariants this class must never break:
 * <ol>
 *   <li>Every tick ends with a scheduled successor, including on failure —
 *       otherwise the chain dies silently and the app looks healthy while
 *       fetching nothing.</li>
 *   <li>The delay is always clamped between {@code minDelay} and
 *       {@code maxDelay} — otherwise a past timestamp becomes a busy loop and
 *       a nonsense future one parks the scheduler for years.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateFetchScheduler {

  private final FetchRateUseCase fetchRateUseCase;
  private final RateHistoryRepository rateHistoryRepository;
  private final TaskScheduler taskScheduler;
  private final Clock clock;
  private final ScheduleProperties scheduleProperties;

  @EventListener(ApplicationReadyEvent.class)
  public void bootstrap() {
    if (!scheduleProperties.enabled()) {
      log.info("rate_scheduler.disabled");
      return;
    }

    log.info(
        "rate_scheduler.started minDelay={} maxDelay={} fallbackDelay={}",
        scheduleProperties.minDelay(),
        scheduleProperties.maxDelay(),
        scheduleProperties.fallbackDelay());

    // First tick runs immediately: after downtime the schedule is stale by
    // definition, and the dedup in the use case makes a redundant fetch cheap.
    scheduleNextRun(clock.instant());
  }

  private void tick() {
    Instant startedAt = clock.instant();
    SyncResult result = null;
    String outcome = "FAILED";

    try {
      result = fetchRateUseCase.execute();
      outcome = result.outcome().name();
    } catch (Exception exception) {
      // Two lines on failure on purpose: the stack trace here, the summary in
      // scheduleNext. Merge them only if you stop needing the trace.
      log.error("rate_scheduler.fetch_failed", exception);
    } finally {
      scheduleNext(outcome, result, startedAt);
    }
  }

  // Xử lý failed khi không có new record
  private void scheduleNext(String outcome, SyncResult result, Instant startedAt) {
    Instant now = clock.instant();

    // TODO(you), US-03 policy: after a FAILED tick no new row exists, so this
    // reads the PREVIOUS row, whose next_update_at is already in the past. That
    // clamps to minDelay (default 1m) and retries ~60 times an hour for the
    // whole outage. Decide: keep it (a slow retry loop) or use fallbackDelay()
    // when outcome is FAILED. Then delete this comment.
    Duration delay = resolveNextDelay(now);
    Instant nextRunAt = now.plus(delay);

    scheduleNextRun(nextRunAt);

    log.info(
        "rate_scheduler.tick outcome={} fetchedAt={} nextRunAt={} delay={} durationMs={}",
        outcome,
        result != null ? result.fetchedAt() : null,
        nextRunAt,
        delay,
        Duration.between(startedAt, clock.instant()).toMillis());
  }

  /**
   * Package-private for tests: given a fixed {@link Clock} and a stubbed
   * repository, every branch (<i>null</i> timestamp, past timestamp, absurd
   * future, DB failure) is testable without sleeping.
   */
  Duration resolveNextDelay(Instant now) {
    try {
      return rateHistoryRepository.findTopByOrderByFetchedAtDesc()
          .map(RateHistory::getNextUpdateAt)
          .map(nextUpdateAt -> Duration.between(now, nextUpdateAt))
          .map(this::clamp)
          .orElseGet(this::fallbackDelay);

    } catch (Exception exception) {
      log.warn("rate_scheduler.next_update_unavailable using fallback={}",
          scheduleProperties.fallbackDelay(), exception);

      return fallbackDelay();
    }
  }

  private Duration clamp(Duration delay) {
    if (delay.compareTo(scheduleProperties.minDelay()) < 0) {
      return scheduleProperties.minDelay();
    }

    if (delay.compareTo(scheduleProperties.maxDelay()) > 0) {
      return scheduleProperties.maxDelay();
    }

    return delay;
  }

  private Duration fallbackDelay() {
    return clamp(scheduleProperties.fallbackDelay());
  }

  /**
   * Never let a rejected schedule escape: during shutdown the executor refuses
   * new tasks, and an exception thrown from {@code finally} would replace the
   * failure that caused it. A rejected schedule means the container is going
   * down, so there is no successor to place.
   */
  private void scheduleNextRun(Instant runAt) {
    try {
      taskScheduler.schedule(this::tick, runAt);
    } catch (TaskRejectedException exception) {
      log.warn("rate_scheduler.rejected runAt={} (shutting down?)", runAt, exception);
    }
  }
}
