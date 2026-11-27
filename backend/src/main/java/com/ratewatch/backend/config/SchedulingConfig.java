package com.ratewatch.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

/**
 * Infrastructure for US-02's self-scheduling loop.
 *
 * <p>Deliberately **no** {@code @EnableScheduling}: the scheduler calls
 * {@link TaskScheduler#schedule} itself, so there is no {@code @Scheduled}
 * method for Spring to proxy. Add {@code @EnableScheduling} only if you later
 * want fixed {@code @Scheduled} methods as well.
 *
 * <p>New to you: {@code TaskScheduler.schedule(task, Instant)} runs one task at
 * one moment. A repeating schedule is built by the task scheduling its own
 * successor — which also means an exception thrown inside the task ends the
 * chain unless the task schedules the next run anyway. That is the trap this
 * config cannot protect you from; it belongs in the scheduler.
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {

  /**
   * UTC so delay arithmetic never depends on the host's zone (the DB stores
   * timestamptz, the provider speaks UTC seconds). Tests inject {@code Clock.fixed}
   * instead of sleeping.
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * One thread on purpose. Two overlapping ticks would race on the same source
   * update, and only the UNIQUE (fetched_at) constraint would stop the duplicate
   * row. A pool of one turns "overlap" into "queue", which is the right
   * behaviour for a job that runs roughly once a day.
   */
  @Bean
  TaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("rate-sync-");
    // Cancelled tasks should not linger in the delayed-task queue forever.
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
