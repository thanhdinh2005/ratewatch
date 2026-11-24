package com.ratewatch.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Binds {@code app.api.*} from application.yaml.
 *
 * <p>{@code baseUrl} is a {@link URI} rather than a {@link String} on purpose:
 * a missing, empty, or relative value then fails at startup with a binding
 * error, instead of surfacing as an "URI is not absolute" exception on the
 * first scheduled call — possibly hours later.
 *
 * <p>Timeouts live here rather than inside {@code RestClient.builder()} in the
 * client class, because they are configuration: they differ between a laptop
 * on hotel wifi and the homelab, and they must be tunable without a rebuild.
 *
 * <p>{@code baseCurrency} is the base the client requests and, separately, the
 * value it expects echoed back in {@code base_code}.
 */
@ConfigurationProperties(prefix = "app.api")
public record RateApiProperties(
  URI baseUrl,
  String baseCurrency,
  Duration connectTimeout,
  Duration readTimeout
) {
}
