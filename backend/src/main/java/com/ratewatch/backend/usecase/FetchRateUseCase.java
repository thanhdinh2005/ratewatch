package com.ratewatch.backend.usecase;

import com.ratewatch.backend.client.RateApiClient;
import com.ratewatch.backend.client.RateApiException;
import com.ratewatch.backend.client.RateApiResponse;
import com.ratewatch.backend.entity.RateHistory;
import com.ratewatch.backend.repository.RateHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class FetchRateUseCase {
  private final RateHistoryRepository rateHistoryRepository;
  private final RateApiClient rateApiClient;

  /**
   * One attempt: fetch, decide, persist.
   *
   * <p>Failures propagate untouched. This method does not decide whether a
   * failure is retryable (US-03) and does not log it (the scheduler logs one
   * line per tick), so nothing gets logged twice.
   */
  @Transactional
  public SyncResult execute() {
    log.debug("rate_sync.start");
    long startNanos = System.nanoTime();

    RateApiResponse response = rateApiClient.fetchRate();

    Instant fetchedAt = Instant.ofEpochSecond(response.timeLastUpdateUnix());

    if (rateHistoryRepository.existsByFetchedAt(fetchedAt)) {
      log.info("rate_sync.skipped fetchedAt={}", fetchedAt);
      return SyncResult.skipped(fetchedAt);
    }

    // Defence in depth: the client already rejects a payload without these.
    // Reusing RateApiException keeps one error vocabulary for "the provider
    // gave us something unusable" instead of inventing an IllegalStateException.
    BigDecimal vnd = response.rates() == null ? null : response.rates().get("VND");
    BigDecimal eur = response.rates() == null ? null : response.rates().get("EUR");
    if (vnd == null || eur == null) {
      throw RateApiException.invalidPayload(
          "rates missing VND or EUR, fetchedAt=" + fetchedAt
      );
    }

    Instant nextUpdateAt = response.timeNextUpdateUnix() == null
        ? null
        : Instant.ofEpochSecond(response.timeNextUpdateUnix());

    RateHistory rateHistory = RateHistory.create(vnd, eur, fetchedAt, nextUpdateAt);
    rateHistoryRepository.save(rateHistory);

    log.info("rate_sync.saved fetchedAt={} id={} durationMs={}",
        fetchedAt, rateHistory.getId(), elapsedMillis(startNanos));
    return SyncResult.saved(fetchedAt);
  }

  private static long elapsedMillis(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
  }
}
