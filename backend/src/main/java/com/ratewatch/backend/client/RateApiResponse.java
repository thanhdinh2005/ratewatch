package com.ratewatch.backend.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The subset of {@code GET {app.api.base-url}/latest/{base}} that this app reads.
 *
 * <p>Deliberately not mapped: {@code provider}, {@code documentation},
 * {@code terms_of_use} (US-08 lives on the frontend), {@code time_eol_unix}
 * (always 0 in the free tier), and the RFC-1123 strings
 * ({@code time_last_update_utc}, {@code time_next_update_utc}) — the unix fields
 * are authoritative by decision, so parsing those strings would only add a
 * failure mode.
 *
 * <p>Timestamps are boxed {@code Long}, not {@code long}: "field absent" must be
 * distinguishable from "field is 0". That distinction matters because the value
 * becomes an idempotency key under a unique constraint.
 *
 * <p>{@code rates} is {@code Map<String, BigDecimal>}, never
 * {@code Map<String, Double>}: Jackson builds a BigDecimal from the JSON token
 * text, while a double would round money values through a binary float.
 *
 * <p>{@code @JsonIgnoreProperties} is explicit even though Boot ignores unknown
 * properties by default, because this record intentionally maps ~7 of the
 * payload's ~12 fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RateApiResponse(
  String result,

  @JsonProperty("error-type")
  String errorType,

  @JsonProperty("base_code")
  String baseCode,

  @JsonProperty("time_last_update_unix")
  Long timeLastUpdateUnix,

  @JsonProperty("time_next_update_unix")
  Long timeNextUpdateUnix,

  Map<String, BigDecimal> rates
) {
}
