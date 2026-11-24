package com.ratewatch.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * One {@link RestClient} per external system.
 *
 * <p>Built here rather than inside {@code RateApiClient.fetchRate()} because:
 * timeouts are configuration, not code; one instance keeps its configuration
 * in one place for the next client (Supabase, US-06); and US-09 can move the
 * base URL via a property without editing the client class.
 *
 * <p>New to you, worth knowing: Boot auto-configures a {@code RestClient.Builder}
 * bean (prototype-scoped, so mutating it per bean is safe) with the
 * observability hooks already applied. We start from that builder instead of
 * {@code RestClient.builder()}, so tracing/metrics keep working later.
 */
@Configuration(proxyBeanMethods = false)
public class RestClientConfig {

  /**
   * The bean name matters once a second {@code RestClient} exists: injection by
   * type would then be ambiguous. It is not a qualifier yet — just a name.
   */
  @Bean
  RestClient rateApiRestClient(RestClient.Builder builder, RateApiProperties properties) {

    // SimpleClientHttpRequestFactory is the JDK URLConnection implementation:
    // no extra dependency. The Duration setters exist since Spring 6.1, so no
    // manual millis conversion. Note: java.net's URLConnection is fine for one
    // small JSON GET; it is not a connection pool and does not do HTTP/2.
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.connectTimeout());
    requestFactory.setReadTimeout(properties.readTimeout());

    return builder
        .baseUrl(properties.baseUrl())
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .requestFactory(requestFactory)
        .build();
  }
}
