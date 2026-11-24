package com.ratewatch.backend.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the entity's invariants and its derivation.
 *
 * <p>No Spring, no database, no mocks: {@code create} is a pure function, so
 * these run in milliseconds and can be looked at as the specification of what
 * a valid history row is.
 *
 * <p>The numbers are the real ones the provider returned on 2026-11-23, so a
 * failure here means real rates would be stored wrong.
 */
class RateHistoryTest {

  private static final BigDecimal VND_PER_USD = new BigDecimal("25779.206142");
  private static final BigDecimal EUR_PER_USD = new BigDecimal("0.911134");
  private static final Instant FETCHED_AT = Instant.ofEpochSecond(1795392152L);
  private static final Instant NEXT_UPDATE_AT = Instant.ofEpochSecond(1795479422L);

  @Test
  void derivesEurToVndFromTheRawQuotes() {
    RateHistory entity = RateHistory.create(VND_PER_USD, EUR_PER_USD, FETCHED_AT, NEXT_UPDATE_AT);

    // 25779.206142 / 0.911134 = 28293.539854..., stored at numeric(18,4)
    assertThat(entity.getUsdToVnd()).isEqualByComparingTo("25779.2061");
    assertThat(entity.getEurToVnd()).isEqualByComparingTo("28293.5399");

    assertThat(entity.getUsdToVnd().scale()).isEqualTo(4);
    assertThat(entity.getEurToVnd().scale()).isEqualTo(4);

    assertThat(entity.getFetchedAt()).isEqualTo(FETCHED_AT);
    assertThat(entity.getNextUpdateAt()).isEqualTo(NEXT_UPDATE_AT);
  }

  @Test
  void acceptsANullNextUpdateBecauseUs02OwnsTheFallback() {
    RateHistory entity = RateHistory.create(VND_PER_USD, EUR_PER_USD, FETCHED_AT, null);

    assertThat(entity.getNextUpdateAt()).isNull();
  }

  @Test
  void rejectsZeroEvenWhenItCarriesAScale() {
    // BigDecimal.equals is scale-sensitive: "0.0000".equals(ZERO) is false,
    // which is how a zero used to slip through an equals(ZERO) check.
    BigDecimal zeroWithScale = new BigDecimal("0.0000");

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
        () -> RateHistory.create(zeroWithScale, EUR_PER_USD, FETCHED_AT, NEXT_UPDATE_AT));

    assertThat(thrown).hasMessageContaining("usdToVnd");
  }

  @Test
  void rejectsNegativeRate() {
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
        () -> RateHistory.create(new BigDecimal("-1"), EUR_PER_USD, FETCHED_AT, null));

    assertThat(thrown).hasMessageContaining("usdToVnd");
  }

  @Test
  void rejectsMissingFetchedAtBecauseItIsTheIdempotencyKey() {
    assertThrows(NullPointerException.class,
        () -> RateHistory.create(VND_PER_USD, EUR_PER_USD, null, NEXT_UPDATE_AT));
  }
}
