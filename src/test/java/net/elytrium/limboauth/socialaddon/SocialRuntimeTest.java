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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.vk.api.sdk.exceptions.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.AbstractSocial;
import net.elytrium.limboauth.socialaddon.social.SocialInitializationException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class SocialRuntimeTest {

  @Test
  void blocked_provider_does_not_delay_another_provider_or_shutdown() throws Exception {
    FakeSocial blocked = new FakeSocial();
    blocked.release = new CountDownLatch(1);
    FakeSocial healthy = new FakeSocial();
    SocialRuntime first = new SocialRuntime("vk", blocked, mock(Logger.class));
    SocialRuntime second = new SocialRuntime("discord", healthy, mock(Logger.class));
    try {
      first.start();
      second.start();
      assertThat(blocked.entered.await(2, TimeUnit.SECONDS)).isTrue();
      await().atMost(Duration.ofSeconds(2)).until(second::isAvailable);
      assertThat(first.isAvailable()).isFalse();
      first.stop();
      blocked.release.countDown();
      await().atMost(Duration.ofSeconds(2)).until(() -> blocked.stops.get() >= 2);
      assertThat(first.isAvailable()).isFalse();
      assertThat(second.isAvailable()).isTrue();
    } finally {
      blocked.release.countDown();
      first.stop();
      second.stop();
    }
  }

  @Test
  void stalled_vk_delivery_does_not_run_on_the_submitting_thread() throws Exception {
    final FakeSocial social = new FakeSocial();
    final SocialRuntime runtime = new SocialRuntime("vk", social, mock(Logger.class));
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<Thread> worker = new AtomicReference<>();
    try {
      runtime.tick();
      runtime.execute(ignored -> {
        worker.set(Thread.currentThread());
        entered.countDown();
        FakeSocial.waitFor(release);
      });
      // Submission returned while delivery is still waiting on the provider.
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(release.getCount()).isEqualTo(1);
      assertThat(worker.get()).isNotSameAs(Thread.currentThread());
      assertThat(runtime.isAvailable()).isTrue();
    } finally {
      release.countDown();
      runtime.stop();
    }
  }

  @Test
  void retry_is_bounded_and_recovers_without_restarting_the_proxy() {
    AtomicLong clock = new AtomicLong();
    FakeSocial social = new FakeSocial();
    social.fail = true;
    SocialRuntime runtime = new SocialRuntime("vk", social, mock(Logger.class), clock::get);
    try {
      runtime.tick();
      runtime.tick();
      assertThat(social.starts.get()).isEqualTo(1);
      assertThat(runtime.isAvailable()).isFalse();
      social.fail = false;
      clock.set(TimeUnit.SECONDS.toNanos(5));
      runtime.tick();
      assertThat(social.starts.get()).isEqualTo(2);
      assertThat(runtime.isAvailable()).isTrue();
      social.healthy = false;
      runtime.tick();
      assertThat(runtime.getState()).isEqualTo("unavailable");
      social.healthy = true;
      runtime.tick();
      assertThat(runtime.isAvailable()).isTrue();
      assertThat(social.starts.get()).isEqualTo(2);
    } finally {
      runtime.stop();
    }
  }

  @Test
  void startup_failure_logs_api_code_once_without_credentials_or_raw_upstream_message() {
    AtomicLong clock = new AtomicLong();
    FakeSocial social = new FakeSocial();
    social.failure = new ApiException(5, "access_token=private-fixture-token");
    social.fail = true;
    Logger logger = mock(Logger.class);
    SocialRuntime runtime = new SocialRuntime("vk", social, logger, clock::get);
    try {
      runtime.tick();
      clock.set(TimeUnit.SECONDS.toNanos(5));
      runtime.tick();
      verify(logger).warn("Social provider {} is unavailable reason={}; proxy startup is not blocked, authentication remains protected",
          "vk", "initialization_vk_api_5");
      verifyNoMoreInteractions(logger);
      assertThat(runtime.isAvailable()).isFalse();
    } finally {
      runtime.stop();
    }
  }

  @Test
  void disabled_provider_is_never_started() {
    FakeSocial social = new FakeSocial();
    social.enabled = false;
    SocialRuntime runtime = new SocialRuntime("vk", social, mock(Logger.class));
    try {
      runtime.start();
      runtime.tick();
      assertThat(social.starts.get()).isZero();
      assertThat(runtime.getState()).isEqualTo("disabled");
    } finally {
      runtime.stop();
    }
  }

  @Test
  void queued_messages_expire_and_are_discarded_after_shutdown() throws Exception {
    AtomicLong clock = new AtomicLong();
    FakeSocial social = new FakeSocial();
    SocialRuntime runtime = new SocialRuntime("telegram", social, mock(Logger.class), clock::get);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger delivered = new AtomicInteger();
    try {
      runtime.tick();
      runtime.execute(ignored -> {
        entered.countDown();
        FakeSocial.waitFor(release);
      });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      for (int index = 0; index < 100; index++) {
        runtime.execute(ignored -> delivered.incrementAndGet());
      }
      clock.set(TimeUnit.SECONDS.toNanos(31));
      release.countDown();
      runtime.stop();
      runtime.execute(ignored -> delivered.incrementAndGet());
      assertThat(delivered.get()).isZero();
    } finally {
      release.countDown();
      runtime.stop();
    }
  }

  static final class FakeSocial extends AbstractSocial {
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger stops = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(0);
    boolean enabled = true;
    volatile boolean healthy;
    volatile boolean fail;
    Exception failure = new IllegalStateException("fixture token must not be logged");

    FakeSocial() {
      super((field, id, message) -> { }, (field, id, message) -> { });
    }

    static void waitFor(CountDownLatch latch) {
      try {
        if (!latch.await(3, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Test latch timed out");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public boolean isEnabled() {
      return this.enabled;
    }

    @Override
    public void start() throws SocialInitializationException {
      this.starts.incrementAndGet();
      this.entered.countDown();
      waitFor(this.release);
      if (this.fail) {
        throw new SocialInitializationException(this.failure);
      }
      this.healthy = true;
    }

    @Override
    public void stop() {
      this.stops.incrementAndGet();
      this.healthy = false;
    }

    @Override
    public boolean isHealthy() {
      return this.healthy;
    }

    @Override
    public String getDbField() {
      return SocialPlayer.TELEGRAM_DB_FIELD;
    }

    @Override
    public void onPlayerAdded(Long id) {
    }

    @Override
    public void onPlayerRemoved(SocialPlayer player) {
    }

    @Override
    public void sendMessage(Long id, String content, List<List<ButtonItem>> buttons, ButtonVisibility visibility) {
    }

    @Override
    public void sendMessage(SocialPlayer player, String content, List<List<ButtonItem>> buttons, ButtonVisibility visibility) {
    }

    @Override
    public boolean canSend(SocialPlayer player) {
      return player.getTelegramID() != null;
    }
  }
}
