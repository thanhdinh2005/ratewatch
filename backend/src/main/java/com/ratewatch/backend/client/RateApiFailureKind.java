package com.ratewatch.backend.client;

/**
 * Why one fetch attempt failed, described by <em>cause</em>.
 *
 * <p>Deliberately contains no {@code retryable} flag. Retryability is a policy
 * decision that belongs to US-03, and it may differ per call site (a scheduled
 * fetch may retry a timeout for 30 s; a request-triggered fetch must not hold
 * a connection that long). Encoding it here would smuggle policy into the
 * client and make every future caller inherit it.
 *
 * <p>New to you: if you later add a value here, the {@code switch} in
 * {@code GlobalExceptionHandler} stops compiling. That is intentional — it
 * forces the mapping to be revisited instead of silently falling through.
 */
public enum RateApiFailureKind {

  /** No HTTP exchange happened: DNS, TLS, connection refused, connect/read timeout. */
  TRANSPORT,

  /** The exchange happened and the provider answered with a non-2xx status. */
  HTTP_STATUS,

  /**
   * HTTP 200, but the payload reports {@code result != "success"}
   * (e.g. {@code error-type: unsupported-code} / {@code quota-reached}).
   */
  PROVIDER_REJECTED,

  /** HTTP 200 + success, but fields this app requires are missing or unusable. */
  INVALID_PAYLOAD
}
