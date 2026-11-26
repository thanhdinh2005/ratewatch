package com.ratewatch.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code app.schedule.*}.
 *
 * <p>Three durations, each covering a different failure of the provider's
 * {@code time_next_update_unix}:
 *
 * <ul>
 *   <li>{@code fallbackDelay} — the field is missing or unparseable (the US-02
 *       "sane fallback").</li>
 *   <li>{@code minDelay} — the computed delay is zero or negative, because the
 *       timestamp is in the past. Without a floor this becomes a busy loop
 *       hammering the provider.</li>
 *   <li>{@code maxDelay} — the timestamp is nonsense or absurdly far away.
 *       Without a ceiling the app parks itself for years and never fetches
 *       again.</li>
 * </ul>
 *
 * <p>{@code enabled} exists so the scheduler can be switched off without
 * deleting code: tests must not call the real provider, and you will want a way
 * to pause ingestion during maintenance.
 *
 * <p>No {@code @DefaultValue} here on purpose: the values live in
 * application.yaml so they can be changed without a rebuild, and a missing key
 * fails at startup instead of silently using a number nobody remembers.
 */
@ConfigurationProperties(prefix = "app.schedule")
public record ScheduleProperties(
        boolean enabled,
        Duration fallbackDelay,
        Duration minDelay,
        Duration maxDelay
) {
}
