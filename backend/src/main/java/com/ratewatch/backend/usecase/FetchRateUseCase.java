package com.ratewatch.backend.usecase;

import com.ratewatch.backend.client.RateApiClient;
import com.ratewatch.backend.client.RateApiResponse;
import com.ratewatch.backend.entity.RateHistory;
import com.ratewatch.backend.repository.RateHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class FetchRateUseCase {
  private final RateHistoryRepository rateHistoryRepository;
  private final RateApiClient rateApiClient;

  @Transactional
  public SyncResult execute() {
    RateApiResponse response = rateApiClient.fetchRate();

    Instant fetchedAt = Instant.ofEpochSecond(response.timeLastUpdateUnix());
    Instant nextUpdateAt = response.timeNextUpdateUnix() == null
      ? null
      : Instant.ofEpochSecond(response.timeNextUpdateUnix());

    if (rateHistoryRepository.existsByFetchedAt(fetchedAt)) {
      return SyncResult.skipped(fetchedAt);
    }

    RateHistory rateHistory = RateHistory.create(
      response.rates().get("VND"),
      response.rates().get("EUR"),
      fetchedAt,
      nextUpdateAt
    );

    rateHistoryRepository.save(rateHistory);
    return SyncResult.saved(fetchedAt);
  }
}
