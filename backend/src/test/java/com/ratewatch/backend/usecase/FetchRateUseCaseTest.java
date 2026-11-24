package com.ratewatch.backend.usecase;

import com.ratewatch.backend.client.RateApiClient;
import com.ratewatch.backend.client.RateApiResponse;
import com.ratewatch.backend.entity.RateHistory;
import com.ratewatch.backend.repository.RateHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the sync decision: save a new source update, skip one that is
 * already stored, and survive a payload without a next-update timestamp.
 *
 * <p>Two mocks, no Spring context, no Docker — this runs in milliseconds.
 *
 * <p>What this class cannot prove: that a concurrent pair of runs cannot both
 * insert. Only the UNIQUE (fetched_at) constraint in the database can promise
 * that, and it needs an integration test with a real Postgres to demonstrate.
 */
@ExtendWith(MockitoExtension.class)
class FetchRateUseCaseTest {

  private static final long LAST_UPDATE_UNIX = 1795392152L;
  private static final long NEXT_UPDATE_UNIX = 1795479422L;
  private static final Instant FETCHED_AT = Instant.ofEpochSecond(LAST_UPDATE_UNIX);

  @Mock
  private RateHistoryRepository rateHistoryRepository;

  @Mock
  private RateApiClient rateApiClient;

  @InjectMocks
  private FetchRateUseCase useCase;

  private static RateApiResponse payload(Long nextUpdateUnix) {
    return new RateApiResponse(
        "success",
        null,
        "USD",
        LAST_UPDATE_UNIX,
        nextUpdateUnix,
        Map.of(
            "VND", new BigDecimal("25779.206142"),
            "EUR", new BigDecimal("0.911134")));
  }

  @Test
  void savesWhenTheSourceUpdateIsNew() {
    given(rateApiClient.fetchRate()).willReturn(payload(NEXT_UPDATE_UNIX));
    given(rateHistoryRepository.existsByFetchedAt(FETCHED_AT)).willReturn(false);

    SyncResult result = useCase.execute();

    assertThat(result.outcome()).isEqualTo(SyncResult.Outcome.SAVED);
    assertThat(result.fetchedAt()).isEqualTo(FETCHED_AT);

    ArgumentCaptor<RateHistory> saved = ArgumentCaptor.forClass(RateHistory.class);
    verify(rateHistoryRepository).save(saved.capture());

    RateHistory entity = saved.getValue();
    assertThat(entity.getUsdToVnd()).isEqualByComparingTo("25779.2061");
    assertThat(entity.getEurToVnd()).isEqualByComparingTo("28293.5399");
    assertThat(entity.getFetchedAt()).isEqualTo(FETCHED_AT);
    assertThat(entity.getNextUpdateAt()).isEqualTo(Instant.ofEpochSecond(NEXT_UPDATE_UNIX));
  }

  @Test
  void skipsWhenTheSourceUpdateIsAlreadyStored() {
    given(rateApiClient.fetchRate()).willReturn(payload(NEXT_UPDATE_UNIX));
    given(rateHistoryRepository.existsByFetchedAt(FETCHED_AT)).willReturn(true);

    SyncResult result = useCase.execute();

    assertThat(result.outcome()).isEqualTo(SyncResult.Outcome.SKIPPED);
    assertThat(result.changed()).isFalse();
    verify(rateHistoryRepository, never()).save(any(RateHistory.class));
  }

  @Test
  void savesWithNullNextUpdateInsteadOfFailing() {
    given(rateApiClient.fetchRate()).willReturn(payload(null));
    given(rateHistoryRepository.existsByFetchedAt(FETCHED_AT)).willReturn(false);

    SyncResult result = useCase.execute();

    assertThat(result.changed()).isTrue();

    ArgumentCaptor<RateHistory> saved = ArgumentCaptor.forClass(RateHistory.class);
    verify(rateHistoryRepository).save(saved.capture());
    assertThat(saved.getValue().getNextUpdateAt()).isNull();
  }
}
