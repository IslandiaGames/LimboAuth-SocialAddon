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

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/** Publishes only fixed provider identities and states, never configuration or credentials. */
final class SocialStatusWriter {

  private final Path path;
  private final List<SocialRuntime> providers;
  private final Logger logger;
  private final Clock clock;
  private final long started;
  private final ScheduledExecutorService executor;
  private boolean closed;
  private boolean failed;

  SocialStatusWriter(Path path, List<SocialRuntime> providers, Logger logger, Clock clock) {
    this.path = path;
    this.providers = List.copyOf(providers);
    this.logger = logger;
    this.clock = clock;
    this.started = clock.instant().getEpochSecond();
    this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "social-status");
      thread.setDaemon(true);
      return thread;
    });
  }

  void start() {
    this.executor.scheduleWithFixedDelay(this::publish, 0, 5, TimeUnit.SECONDS);
  }

  synchronized void publish() {
    if (this.closed) {
      return;
    }
    Path temporary = this.path.resolveSibling(this.path.getFileName() + ".tmp");
    try {
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("version", 1);
      snapshot.put("started_at", this.started);
      snapshot.put("updated_at", this.clock.instant().getEpochSecond());
      snapshot.put("providers", this.providers.stream().map(provider -> Map.of(
          "name", provider.getName(), "enabled", provider.isEnabled(), "state", provider.getState())).toList());
      Path parent = this.path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(temporary, new Gson().toJson(snapshot), StandardCharsets.UTF_8);
      Files.move(temporary, this.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      if (this.failed) {
        this.logger.info("Social provider status publication recovered");
      }
      this.failed = false;
    } catch (IOException error) {
      if (!this.failed) {
        this.logger.warn("Social provider status publication failed");
      }
      this.failed = true;
    }
  }

  synchronized void stop() {
    this.closed = true;
    this.executor.shutdownNow();
  }
}
