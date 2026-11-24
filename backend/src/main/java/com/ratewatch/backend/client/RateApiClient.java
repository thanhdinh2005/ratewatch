package com.ratewatch.backend.client;

import com.ratewatch.backend.config.RateApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the rate provider and turns every way that call can fail into a
 * {@link RateApiException}.
 *
 * <p>What this class does NOT do, on purpose: no retry, no backoff, no sleeping
 * (US-03), no derivation of EUR→VND (that needs a rounding decision), no
 * persistence (US-04). It fetches and validates, nothing else.
 *
 * <p>The {@link RestClient} is injected, not built here — see
 * {@code config/RestClientConfig} for why (timeouts, one configuration point,
 * US-09 base-URL switch).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateApiClient {

  private static final String EXPECTED_RESULT = "success";
  private static final String VND = "VND";
  private static final String EUR = "EUR";

  private final RestClient rateApiRestClient;
  private final RateApiProperties properties;

  public RateApiResponse fetchRate() {
    RateApiResponse response = request();
    requireSuccess(response);
    requireUsable(response);
    return response;
  }

  /**
   * The three failure families of a real HTTP call. Order matters: the specific
   * {@link RestClientException} subclasses are caught before their parent.
   *
   * <p>Note that {@link RateApiException} is NOT a {@link RestClientException},
   * so a throw from inside this try block is not swallowed by the catches below.
   */
  private RateApiResponse request() {
    log.debug("GET {} (base {})",
        properties.baseUrl() + "/latest/" + properties.baseCurrency(),
        properties.baseCurrency()
    );

    try {
      RateApiResponse body = rateApiRestClient.get()
        // pathSegment both encodes and avoids the base-path merge question:
        // no leading slash means nothing can "replace" the /v6 segment.
        .uri(builder -> builder.pathSegment("latest", properties.baseCurrency()).build())
        .retrieve()
        .body(RateApiResponse.class);

      if (body == null) {
        // 2xx with an empty body. Deliberately not Body.requiredBody(): that
        // throws IllegalStateException, which is indistinguishable from our
        // own bugs.
        throw RateApiException.invalidPayload("empty response body");
      }
      return body;

    } catch (RestClientResponseException ex) {
      // 4xx/5xx: retrieve() raises this before a body is decoded.
      throw RateApiException.httpStatus(
          ex.getStatusCode().value(), ex.getResponseBodyAsString(), ex);

    } catch (ResourceAccessException ex) {
      // Connect timeout, read timeout, DNS, TLS, connection reset.
      throw RateApiException.transport(ex.getMessage(), ex);

    } catch (RestClientException ex) {
      // Content-type / message-conversion problems that never became a status.
      throw RateApiException.invalidPayload("unreadable response: " + ex.getMessage());
    }
  }

  /**
   * HTTP 200 does not mean success. The free tier answers errors with
   * {@code {"result":"error","error-type":"..."}} and no timestamps at all,
   * so this check must run before any field is touched.
   */
  private void requireSuccess(RateApiResponse response) {
    if (!EXPECTED_RESULT.equals(response.result())) {
      throw RateApiException.providerRejected(response.errorType());
    }
  }

  /**
   * Rejects a success payload this app cannot use, at the boundary where the
   * cause is still known.
   *
   * <p>{@code time_next_update_unix} is intentionally NOT required: US-02 owns
   * the fallback for a missing/invalid next-update field. {@code time_last_update_unix}
   * IS required, because it becomes the idempotency key — and an absent or zero
   * key would poison a unique constraint rather than fail loudly.
   */
  private void requireUsable(RateApiResponse response) {
    if (!properties.baseCurrency().equals(response.baseCode())) {
      throw RateApiException.invalidPayload(
          "base_code was " + response.baseCode() + ", expected " + properties.baseCurrency());
    }
    if (response.rates() == null) {
      throw RateApiException.invalidPayload("rates missing");
    }
    // Both are needed: VND is one leg of each pair, EUR is the divisor for the
    // derived EUR→VND rate.
    if (response.rates().get(VND) == null || response.rates().get(EUR) == null) {
      throw RateApiException.invalidPayload("rates missing " + VND + " or " + EUR);
    }
    Long lastUpdate = response.timeLastUpdateUnix();
    if (lastUpdate == null || lastUpdate <= 0) {
      throw RateApiException.invalidPayload("time_last_update_unix was " + lastUpdate);
    }
  }
}
