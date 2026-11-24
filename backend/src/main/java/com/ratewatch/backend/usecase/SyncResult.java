package com.ratewatch.backend.usecase;

import java.time.Instant;

/**
 * What one sync attempt did, as a value the caller can switch on.
 *
 * <p>Exists so that "nothing changed because the source had not updated yet"
 * is a normal return value instead of an exception — for a scheduler that runs
 * every few minutes, it is the most common outcome by far.
 *
 * <p>Failures are deliberately NOT part of this type: they stay exceptions
 * ({@code RateApiException} from the client), because a failure needs a
 * different response from a caller than a no-op does.
 *
 * <p>New to you: a record here is the "sealed vocabulary" half of the error
 * model. If US-03 later wants retry attempts reported, that is the moment to
 * add a field — not to add a third outcome that means "it failed".
 */
public record SyncResult(Outcome outcome, Instant fetchedAt) {

  public enum Outcome {
    /** A new history row was written. */
    SAVED,
    /** The source update at {@code fetchedAt} was already stored. */
    SKIPPED
  }

  public static SyncResult saved(Instant fetchedAt) {
    return new SyncResult(Outcome.SAVED, fetchedAt);
  }

  public static SyncResult skipped(Instant fetchedAt) {
    return new SyncResult(Outcome.SKIPPED, fetchedAt);
  }

  public boolean changed() {
    return outcome == Outcome.SAVED;
  }
}
