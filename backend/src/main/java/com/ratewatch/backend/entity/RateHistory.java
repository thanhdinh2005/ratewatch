package com.ratewatch.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "rate_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class RateHistory {

  private static final int STORE_SCALE = 4;
  private static final int COMPUTE_SCALE = 6;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "usd_to_vnd", nullable = false, precision = 18, scale = 4)
  private BigDecimal usdToVnd;

  @Column(name = "eur_to_vnd", nullable = false, precision = 18, scale = 4)
  private BigDecimal eurToVnd;

  /**
   * The provider's time_last_update_unix — when the SOURCE data changed, not
   * when we fetched. This is also the idempotency key: V1__init.sql puts a
   * unique constraint on it, so a retry that resolves to the same source
   * update cannot create a second history row.
   */
  @Column(name = "fetched_at", nullable = false, updatable = false)
  private Instant fetchedAt;

  /**
   * The provider's time_next_update_unix. Nullable on purpose: US-02 owns the
   * fallback when the field is missing or invalid, and rejecting the row here
   * would throw away an otherwise usable rate.
   */
  @Column(name = "next_update_at")
  private Instant nextUpdateAt;

  /** Owned by the database (default now()); not read back after insert. */
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  /**
   * Builds one history row from the provider's raw quotes.
   *
   * <p>Takes the raw USD→EUR quote and derives EUR→VND internally. That is
   * deliberate: the two numbers look interchangeable (both are "a EUR-ish
   * rate") and passing the wrong one stores 0.91 instead of ~28293 without
   * anything failing.
   *
   * @param usdToVnd     provider's VND per 1 USD
   * @param usdToEur     provider's EUR per 1 USD — NOT the EUR→VND rate
   * @param fetchedAt    provider's time_last_update_unix; the idempotency key
   * @param nextUpdateAt provider's time_next_update_unix; may be null
   */
  public static RateHistory create(
      BigDecimal usdToVnd,
      BigDecimal usdToEur,
      Instant fetchedAt,
      Instant nextUpdateAt
  ) {

    requirePositive(usdToVnd, "usdToVnd");
    requirePositive(usdToEur, "usdToEur");
    Objects.requireNonNull(fetchedAt, "fetchedAt");

    RateHistory entity = new RateHistory();
    entity.usdToVnd = usdToVnd.setScale(STORE_SCALE, ROUNDING);
    entity.eurToVnd = usdToVnd.divide(usdToEur, COMPUTE_SCALE, ROUNDING)
        .setScale(STORE_SCALE, ROUNDING);
    entity.fetchedAt = fetchedAt;
    entity.nextUpdateAt = nextUpdateAt;
    return entity;
  }

  /**
   * signum(), not equals(BigDecimal.ZERO): BigDecimal.equals is scale-sensitive,
   * so "0.0000".equals(ZERO) is false and a zero would slip through.
   */
  private static void requirePositive(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(field + " must be > 0, was " + value);
    }
  }
}
