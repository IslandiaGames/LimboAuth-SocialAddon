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

package net.elytrium.limboauth.socialaddon.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.elytrium.commons.config.Placeholders;
import net.elytrium.commons.kyori.serialization.Serializer;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.event.PostAuthorizationEvent;
import net.elytrium.limboauth.event.PreAuthorizationEvent;
import net.elytrium.limboauth.event.TaskEvent;
import net.elytrium.limboauth.socialaddon.Addon;
import net.elytrium.limboauth.socialaddon.Settings;
import net.elytrium.limboauth.socialaddon.SocialManager;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.SocialButtonListenerAdapter;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LimboAuthAvailabilityTest {

  @Test
  void unavailable_vk_never_bypasses_enabled_two_factor_authentication() throws Exception {
    try (final Fixture fixture = new Fixture(false)) {
      when(fixture.manager.canDeliver(fixture.social)).thenReturn(false);
      fixture.listener.onAuthCompleted(fixture.post);
      verify(fixture.post).setResult(TaskEvent.Result.WAIT);
      verify(fixture.post).completeAndCancel(any(Component.class));
      verify(fixture.post, never()).complete(TaskEvent.Result.NORMAL);
    }
  }

  @Test
  void ordinary_password_login_does_not_wait_for_optional_vk() throws Exception {
    try (final Fixture fixture = new Fixture(false)) {
      fixture.social.setTotpEnabled(false);
      fixture.listener.onAuth(fixture.pre);
      fixture.listener.onAuthCompleted(fixture.post);
      verify(fixture.dao, times(2)).queryForId("fixture");
      verifyNoInteractions(fixture.manager, fixture.plugin);
      verify(fixture.pre, never()).setResult(any(TaskEvent.Result.class));
      verify(fixture.post, never()).setResult(any(TaskEvent.Result.class));
      verify(fixture.post, never()).completeAndCancel(any(Component.class));
    }
  }

  @Test
  void password_login_requires_one_explicit_social_confirmation_after_post_auth() throws Exception {
    try (final Fixture fixture = new Fixture(false)) {
      fixture.listener.onAuth(fixture.pre);
      verifyNoInteractions(fixture.plugin);
      verify(fixture.pre, never()).setResult(TaskEvent.Result.WAIT);
      fixture.listener.onAuthCompleted(fixture.post);
      verify(fixture.post).setResult(TaskEvent.Result.WAIT);
      verify(fixture.post, never()).complete(any(TaskEvent.Result.class));
      fixture.buttons.get("ask_yes").accept(SocialPlayer.VK_DB_FIELD, 123L);
      fixture.buttons.get("ask_yes").accept(SocialPlayer.VK_DB_FIELD, 123L);
      verify(fixture.post, times(1)).complete(TaskEvent.Result.NORMAL);
    }
  }

  @Test
  void blocked_passwordless_account_never_overwrites_cancellation_with_wait() throws Exception {
    try (final Fixture fixture = new Fixture(true)) {
      fixture.social.setBlocked(true);
      fixture.listener.onAuth(fixture.pre);
      verify(fixture.pre).cancel(any(Component.class));
      verify(fixture.pre, never()).setResult(TaskEvent.Result.WAIT);
      verifyNoInteractions(fixture.plugin);
    }
  }

  @Test
  void disconnect_from_limbo_discards_pending_confirmation_without_a_database_read() throws Exception {
    try (final Fixture fixture = new Fixture(false)) {
      fixture.listener.onAuthCompleted(fixture.post);
      clearInvocations(fixture.dao);
      final DisconnectEvent disconnected = mock(DisconnectEvent.class);
      when(disconnected.getPlayer()).thenReturn(fixture.player);
      fixture.listener.onPlayerLeave(disconnected);
      verifyNoInteractions(fixture.dao);
      fixture.buttons.get("ask_yes").accept(SocialPlayer.VK_DB_FIELD, 123L);
      verify(fixture.post, never()).complete(TaskEvent.Result.NORMAL);
    }
  }

  @Test
  void reload_cancels_pending_two_factor_and_ignores_late_confirmation() throws Exception {
    try (final Fixture fixture = new Fixture(false)) {
      fixture.listener.onAuthCompleted(fixture.post);
      fixture.listener.stop();
      fixture.buttons.get("ask_yes").accept(SocialPlayer.VK_DB_FIELD, 123L);
      verify(fixture.post).completeAndCancel(any(Component.class));
      verify(fixture.post, never()).complete(TaskEvent.Result.NORMAL);
    }
  }

  @Test
  void stop_during_availability_check_cannot_publish_a_new_waiting_session() throws Exception {
    try (final Fixture fixture = new Fixture(false)) {
      final CountDownLatch entered = new CountDownLatch(1);
      final CountDownLatch release = new CountDownLatch(1);
      final ExecutorService executor = Executors.newSingleThreadExecutor();
      when(fixture.manager.canDeliver(fixture.social)).thenAnswer(invocation -> {
        entered.countDown();
        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
        return true;
      });
      try {
        final Future<?> authorization = executor.submit(() -> fixture.listener.onAuthCompleted(fixture.post));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        fixture.listener.stop();
        release.countDown();
        authorization.get(5, TimeUnit.SECONDS);
        fixture.buttons.get("ask_yes").accept(SocialPlayer.VK_DB_FIELD, 123L);
        verify(fixture.post, times(1)).completeAndCancel(any(Component.class));
        verify(fixture.post, never()).complete(TaskEvent.Result.NORMAL);
        verify(fixture.player, never()).sendMessage(any(Component.class));
      } finally {
        release.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void duplicate_pending_request_cannot_replace_the_existing_owner() throws Exception {
    try (final Fixture fixture = new Fixture(false)) {
      fixture.listener.onAuthCompleted(fixture.post);
      final PostAuthorizationEvent duplicate = mock(PostAuthorizationEvent.class);
      when(duplicate.getPlayer()).thenReturn(fixture.limboPlayer);
      fixture.listener.onAuthCompleted(duplicate);
      verify(duplicate).completeAndCancel(any(Component.class));
      fixture.buttons.get("ask_yes").accept(SocialPlayer.VK_DB_FIELD, 123L);
      verify(fixture.post, times(1)).complete(TaskEvent.Result.NORMAL);
      verify(duplicate, never()).complete(TaskEvent.Result.NORMAL);
    }
  }

  private static final class Fixture implements AutoCloseable {

    private final Player player = mock(Player.class);
    private final LimboPlayer limboPlayer = mock(LimboPlayer.class);
    private final LimboAuth plugin = mock(LimboAuth.class);
    private final SocialManager manager = mock(SocialManager.class);
    private final SocialPlayer social;
    private final PreAuthorizationEvent pre = mock(PreAuthorizationEvent.class);
    private final PostAuthorizationEvent post = mock(PostAuthorizationEvent.class);
    private final Map<String, SocialButtonListenerAdapter> buttons = new HashMap<>();
    private final Dao<SocialPlayer, String> dao;
    private final LimboAuthListener listener;

    @SuppressWarnings("unchecked")
    private Fixture(final boolean passwordless) throws Exception {
      Settings.IMP.MAIN = new Settings.MAIN();
      Settings.IMP.MAIN.STRINGS = new Settings.MAIN.STRINGS();
      Settings.IMP.MAIN.AUTH_2FA_WITHOUT_PASSWORD = passwordless;
      Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_VALIDATE = "Confirm {IP} {LOCATION}";
      Placeholders.addPlaceholders(Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_VALIDATE, "{IP}", "{LOCATION}");
      this.dao = mock(Dao.class);
      this.social = new SocialPlayer("fixture");
      this.social.setTotpEnabled(true);
      this.social.setVkID(123L);
      when(this.player.getUsername()).thenReturn("Fixture");
      when(this.player.isActive()).thenReturn(true);
      when(this.player.getCurrentServer()).thenReturn(Optional.empty());
      when(this.player.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 25565));
      when(this.limboPlayer.getProxyPlayer()).thenReturn(this.player);
      when(this.pre.getPlayer()).thenReturn(this.player);
      when(this.post.getPlayer()).thenReturn(this.limboPlayer);
      when(this.dao.queryForId("fixture")).thenReturn(this.social);
      when(this.dao.queryForEq(SocialPlayer.VK_DB_FIELD, 123L)).thenReturn(List.of(this.social));
      when(this.manager.canDeliver(this.social)).thenReturn(true);
      doAnswer(invocation -> {
        this.buttons.put(invocation.getArgument(0), invocation.getArgument(1));
        return null;
      }).when(this.manager).addButtonEvent(any(String.class), any(SocialButtonListenerAdapter.class));
      final Serializer serializer = mock(Serializer.class);
      when(serializer.deserialize(any(String.class))).thenReturn(Component.empty());
      try (final MockedStatic<Addon> addon = mockStatic(Addon.class)) {
        addon.when(Addon::getSerializer).thenReturn(serializer);
        this.listener = new LimboAuthListener(null, this.plugin, this.dao, this.manager, List.of(), null);
      }
      clearInvocations(this.manager);
    }

    @Override
    public void close() {
      this.listener.stop();
      Placeholders.removePlaceholders(Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_VALIDATE);
    }
  }
}
