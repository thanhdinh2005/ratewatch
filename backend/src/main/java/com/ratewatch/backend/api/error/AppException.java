package com.ratewatch.backend.api.error;

import lombok.Getter;

/**
 * The single application exception type, carrying an {@link ErrorCode}.
 *
 * <p>Two message channels on purpose:
 * <ul>
 *   <li>{@link #getErrorCode()}'s {@code defaultMessage} — what the client sees;
 *       stable, safe, translatable by the frontend.</li>
 *   <li>{@code getMessage()} / the cause — for logs only; may contain internals
 *       (a URL, a status, a stack trace) that must not reach the browser.</li>
 * </ul>
 * The handler in this package respects that split. Keep it that way.
 *
 * <p>New to you: no constructor takes a raw message alone, because that would
 * let a detail string leak out through the response with no {@code ErrorCode}
 * behind it.
 *
 * <p>TODO(you): decide whether {@code RateApiClient} throws these directly or
 * its own internal failure type (AGENTS.md §8). Whatever you choose, only one
 * of the two should cross the client boundary.
 */
@Getter
public class AppException extends RuntimeException {

  private final ErrorCode errorCode;

  /** Maps straight onto the error code's default message. */
  public AppException(ErrorCode errorCode) {
    super(errorCode.getDefaultMessage());
    this.errorCode = errorCode;
  }

  /** Keeps the cause for logging; the client still sees only the default message. */
  public AppException(ErrorCode errorCode, Throwable cause) {
    super(errorCode.getDefaultMessage(), cause);
    this.errorCode = errorCode;
  }

  /**
   * Adds a log-only detail. Note {@code getMessage()} becomes {@code logDetail},
   * so the handler must never serialise {@code getMessage()} — only
   * {@code getErrorCode().getDefaultMessage()}.
   */
  public AppException(ErrorCode errorCode, String logDetail, Throwable cause) {
    super(logDetail, cause);
    this.errorCode = errorCode;
  }
}
