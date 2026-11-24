package com.ratewatch.backend.api.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing error identity: {@code code} is the contract the
 * frontend switches on; {@code defaultMessage} is the safe, human-readable
 * default; {@code status} is the HTTP status for THAT client.
 *
 * <p>New to you (worth flagging): this enum models the <em>web</em> layer
 * (US-07 responses). It is not the same thing as "why did the external call
 * fail" — a scheduled run has no HTTP response to send and does not care that
 * a failure would have been a 503. See the TODO in {@code RateApiClient} and
 * AGENTS.md §8 before reusing these codes internally.
 *
 * <p>TODO(you): add codes as US-07 actually needs them, and delete the ones it
 * does not. Placeholder set is intentionally small.
 */
@Getter
public enum ErrorCode {

  INTERNAL_ERROR("RW-INTERNAL", HttpStatus.INTERNAL_SERVER_ERROR,
      "Unexpected server error"),

  RATE_PROVIDER_UNAVAILABLE("RW-PROVIDER-UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
      "Exchange rate provider is unavailable"),

  RATE_PROVIDER_BAD_PAYLOAD("RW-PROVIDER-BAD-PAYLOAD", HttpStatus.BAD_GATEWAY,
      "Exchange rate provider returned an unusable payload"),

  RATE_HISTORY_NOT_FOUND("RW-HISTORY-NOT-FOUND", HttpStatus.NOT_FOUND,
      "No rate history for the requested range");

  private final String code;
  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(String code, HttpStatus status, String defaultMessage) {
    this.code = code;
    this.status = status;
    this.defaultMessage = defaultMessage;
  }
}
