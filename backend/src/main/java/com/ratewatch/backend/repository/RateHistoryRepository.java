package com.ratewatch.backend.repository;

import com.ratewatch.backend.entity.RateHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

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

  /**
   * The newest history row. This is the only place {@code next_update_at}
   * survives a restart, so US-02's scheduler reads it on startup instead of
   * keeping the next run time in a field it would lose on every deploy.
   *
   * <p>Derived query name: {@code findTopBy} + {@code OrderByFetchedAtDesc}.
   * The property must be spelled exactly as on the entity — a typo here fails
   * at startup with {@code PropertyReferenceException}, not at compile time.
   */
  Optional<RateHistory> findTopByOrderByFetchedAtDesc();
}
