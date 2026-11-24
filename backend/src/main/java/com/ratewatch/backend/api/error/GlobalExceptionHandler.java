package com.ratewatch.backend.api.error;

import com.ratewatch.backend.client.RateApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into {@link ErrorResponse} bodies.
 *
 * <p>Two models meet here and nowhere else:
 * <ul>
 *   <li>{@link AppException} — a failure this application decided on, already
 *       carrying the {@link ErrorCode} the client should see.</li>
 *   <li>{@link RateApiException} — a failure the external provider caused,
 *       described by cause. This class is the only place that translates it
 *       into a web status.</li>
 * </ul>
 *
 * <p>Most of the time a scheduled run never reaches this advice: the scheduler
 * catches {@code RateApiException} and retries (US-03). The handler below
 * matters only once something request-triggered calls the provider.
 *
 * <p>New to you: {@code @RestControllerAdvice} applies to controller requests
 * only. Exceptions thrown inside a {@code @Scheduled} method do not pass
 * through here — they surface in a log and nowhere else.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(AppException.class)
  ResponseEntity<ErrorResponse> handleAppException(AppException ex, HttpServletRequest request) {
    ErrorCode errorCode = ex.getErrorCode();

    // getMessage() may be a log-only detail, so it goes to the log, not the body.
    log.warn("handled {} at {}: {}", errorCode.getCode(), request.getRequestURI(), ex.getMessage());

    return ResponseEntity.status(errorCode.getStatus())
        .body(ErrorResponse.of(errorCode, request.getRequestURI()));
  }

  /**
   * Provider failures, translated by cause. No {@code default} branch: a new
   * {@link com.ratewatch.backend.client.RateApiFailureKind} value must be
   * handled deliberately, so the compiler refuses to build until it is.
   */
  @ExceptionHandler(RateApiException.class)
  ResponseEntity<ErrorResponse> handleRateApi(RateApiException ex, HttpServletRequest request) {
    ErrorCode errorCode = switch (ex.getKind()) {
      // Nothing usable came back, but the provider may recover: 503.
      case TRANSPORT, HTTP_STATUS -> ErrorCode.RATE_PROVIDER_UNAVAILABLE;
      // Something came back and it is wrong: 502.
      case PROVIDER_REJECTED, INVALID_PAYLOAD -> ErrorCode.RATE_PROVIDER_BAD_PAYLOAD;
    };

    log.warn("provider failure {} at {}: {}",
        ex.getKind(), request.getRequestURI(), ex.getMessage());

    return ResponseEntity.status(errorCode.getStatus())
        .body(ErrorResponse.of(errorCode, request.getRequestURI()));
  }

  /**
   * Last resort. Two things to know before you keep this:
   *
   * <p>1. A catch-all {@code Exception} handler also swallows exceptions Spring
   * MVC already maps correctly — 405 method not allowed, 415 unsupported media
   * type, 400 malformed JSON. They all become 500s. The usual ways out:
   * extend {@code ResponseEntityExceptionHandler}, or re-throw
   * {@code ErrorResponseException} so Spring handles it first.
   *
   * <p>2. Logging the full stack trace here is right; putting any of it in the
   * body is not.
   *
   * <p>TODO(you): choose one of the two options above, or accept 500s for
   * framework errors on purpose and write that down in AGENTS.md §8.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("unhandled exception at {}", request.getRequestURI(), ex);

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, request.getRequestURI()));
  }
}
