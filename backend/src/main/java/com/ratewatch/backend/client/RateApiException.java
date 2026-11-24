package com.ratewatch.backend.client;

import lombok.Getter;

/**
 * The internal failure type of {@code client/}. It answers "what went wrong on
 * this attempt", not "what should the browser see".
 *
 * <p>New to you: there is one class and one enum instead of one exception class
 * per cause. Adding a subclass per cause looks tidier until the retry loop
 * needs three {@code instanceof} checks and a new cause silently inherits the
 * wrong behaviour. A single type + a {@code kind} field keeps the compiler
 * honest: every new kind breaks every switch that must handle it.
 *
 * <p>Fields that are not always known are boxed ({@link Integer},
 * {@link String}) and null when inapplicable — a transport failure has no HTTP
 * status, and a provider rejection may arrive without an {@code error-type}.
 *
 * <p>Nothing here imports {@code api.error} or {@code jakarta.persistence}:
 * the client does not know about the web layer or the database.
 */
@Getter
public class RateApiException extends RuntimeException {

  private final RateApiFailureKind kind;

  /** Non-null only for {@link RateApiFailureKind#HTTP_STATUS}. */
  private final Integer httpStatus;

  /** Non-null only for {@link RateApiFailureKind#PROVIDER_REJECTED}, when the provider sent one. */
  private final String providerErrorType;

  private RateApiException(
      RateApiFailureKind kind,
      String message,
      Integer httpStatus,
      String providerErrorType,
      Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.httpStatus = httpStatus;
    this.providerErrorType = providerErrorType;
  }

  public static RateApiException transport(String detail, Throwable cause) {
    return new RateApiException(
        RateApiFailureKind.TRANSPORT,
        "transport failure: " + detail,
        null, null, cause);
  }

  public static RateApiException httpStatus(int status, String responseBody, Throwable cause) {
    // The body is kept short: it is provider HTML/JSON, not something to log whole.
    String detail = responseBody == null || responseBody.isBlank()
        ? "no body"
        : abbreviate(responseBody);
    return new RateApiException(
        RateApiFailureKind.HTTP_STATUS,
        "provider answered HTTP " + status + " (" + detail + ")",
        status, null, cause);
  }

  public static RateApiException providerRejected(String providerErrorType) {
    return new RateApiException(
        RateApiFailureKind.PROVIDER_REJECTED,
        "provider rejected the request: "
            + (providerErrorType == null ? "no error-type given" : providerErrorType),
        null, providerErrorType, null);
  }

  public static RateApiException invalidPayload(String detail) {
    return new RateApiException(
        RateApiFailureKind.INVALID_PAYLOAD,
        "unusable payload: " + detail,
        null, null, null);
  }

  private static String abbreviate(String body) {
    String singleLine = body.replaceAll("\\s+", " ").trim();
    return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "…";
  }
}
