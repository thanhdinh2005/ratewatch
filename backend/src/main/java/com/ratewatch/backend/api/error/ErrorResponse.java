package com.ratewatch.backend.api.error;

import java.time.Instant;

/**
 * The error body the frontend receives. Deliberately tiny and flat.
 *
 * <p>New to you: Spring 6+ also offers {@code ProblemDetail} (RFC 9457) with a
 * {@code @RestControllerAdvice} built in. This record is the "my own shape"
 * option — pick one and stay with it, because the frontend will depend on it.
 *
 * <p>TODO(you): decide whether {@code timestamp} belongs here. It is handy for
 * correlating with server logs, but it also makes every response unique, which
 * makes HTTP caching of errors impossible.
 */
public record ErrorResponse(
    String code,
    String message,
    Instant timestamp,
    String path
) {

  public static ErrorResponse of(ErrorCode errorCode, String path) {
    return new ErrorResponse(
        errorCode.getCode(),
        errorCode.getDefaultMessage(),
        Instant.now(),
        path);
  }
}
