package com.ratewatch.backend.repository;

import com.ratewatch.backend.entity.RateHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface RateHistoryRepository extends JpaRepository<RateHistory, Long> {

  /**
   * Derived query: counts as a "does this source update already have a row".
   *
   * <p>Note what this is NOT: it is not the deduplication guarantee. The
   * UNIQUE (fetched_at) constraint in V1__init.sql is. Between this check and
   * the insert, another writer can pass the same check — the database is the
   * only thing that can refuse the second row, and that refusal arrives as a
   * DataIntegrityViolationException at flush/commit time.
   */
  boolean existsByFetchedAt(Instant fetchedAt);
}
