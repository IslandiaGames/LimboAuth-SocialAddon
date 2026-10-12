/*
 * Copyright (C) 2022 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboauth.socialaddon;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.elytrium.limboauth.socialaddon.social.AbstractSocial;
import org.slf4j.Logger;

/** Owns one provider; another provider never waits for its network or retry. */
final class SocialRuntime {

  private final String name;
  private final AbstractSocial social;
  private final boolean enabled;
  private final Logger logger;
  private final ScheduledExecutorService lifecycle;
  private final ThreadPoolExecutor messages;
  private volatile boolean closed;
  private volatile boolean started;
  private volatile String state;
  private final LongSupplier clock;
  private long retryAt;
  private int retrySeconds = 5;

  SocialRuntime(String name, AbstractSocial social, Logger logger) {
    this(name, social, logger, System::nanoTime);
  }

  SocialRuntime(String name, AbstractSocial social, Logger logger, LongSupplier clock) {
    this.clock = clock;
    this.name = name;
    this.social = social;
    this.enabled = social.isEnabled();
    this.logger = logger;
    this.state = this.enabled ? "starting" : "disabled";
    this.lifecycle = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "social-" + name + "-lifecycle");
      thread.setDaemon(true);
      return thread;
    });
    this.messages = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
      Thread thread = new Thread(task, "social-" + name + "-messages");
      thread.setDaemon(true);
      return thread;
    });
  }

  void start() {
    this.logger.info("Social provider {} enabled={}", this.name, this.enabled);
    if (this.enabled) {
      this.lifecycle.scheduleWithFixedDelay(this::tick, 0, 5, TimeUnit.SECONDS);
    }
  }

  void tick() {
    if (this.closed || !this.enabled) {
      return;
    }
    if (this.started && !this.social.isRunning()) {
      this.social.stop();
      this.started = false;
    }
    if (this.started) {
      this.transition(this.social.isHealthy() ? "available" : "unavailable");
      return;
    }
    if (this.clock.getAsLong() < this.retryAt) {
      return;
    }
    try {
      this.social.start();
      if (this.closed) {
        this.social.stop();
        return;
      }
      this.started = true;
      this.retrySeconds = 5;
      this.transition(this.social.isHealthy() ? "available" : "unavailable");
    } catch (Exception error) {
      this.social.stop();
      if (!this.closed) {
        this.transition("unavailable");
        this.retryAt = this.clock.getAsLong() + TimeUnit.SECONDS.toNanos(this.retrySeconds);
        this.retrySeconds = Math.min(60, this.retrySeconds * 2);
      }
    }
  }

  private void transition(String next) {
    if (!next.equals(this.state)) {
      this.state = next;
      if ("available".equals(next)) {
        this.logger.info("Social provider {} is available", this.name);
      } else {
        this.logger.warn("Social provider {} is unavailable; proxy startup is not blocked, authentication remains protected", this.name);
      }
    }
  }

  boolean isAvailable() {
    return !this.closed && this.started && this.social.isHealthy();
  }

  AbstractSocial getSocial() {
    return this.social;
  }

  String getName() {
    return this.name;
  }

  boolean isEnabled() {
    return this.enabled;
  }

  String getState() {
    return this.enabled && this.started ? (this.isAvailable() ? "available" : "unavailable") : this.state;
  }

  void execute(Consumer<AbstractSocial> action) {
    if (!this.isAvailable()) {
      return;
    }
    long deadline = this.clock.getAsLong() + TimeUnit.SECONDS.toNanos(30);
    try {
      this.messages.execute(() -> {
        if (this.isAvailable() && this.clock.getAsLong() < deadline) {
          try {
            action.accept(this.social);
          } catch (RuntimeException error) {
            this.logger.warn("Social provider {} could not deliver an account operation", this.name);
          }
        }
      });
    } catch (RejectedExecutionException error) {
      if (!this.closed) {
        this.logger.warn("Social provider {} message queue is full", this.name);
      }
    }
  }

  void stop() {
    this.closed = true;
    this.started = false;
    this.lifecycle.shutdownNow();
    this.messages.shutdownNow();
    this.social.stop();
  }
}
