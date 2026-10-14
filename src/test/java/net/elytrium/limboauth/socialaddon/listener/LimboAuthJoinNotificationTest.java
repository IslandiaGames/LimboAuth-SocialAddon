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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.elytrium.commons.config.Placeholders;
import net.elytrium.commons.kyori.serialization.Serializer;
import net.elytrium.limboauth.socialaddon.Addon;
import net.elytrium.limboauth.socialaddon.Settings;
import net.elytrium.limboauth.socialaddon.SocialManager;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LimboAuthJoinNotificationTest {

  private final List<Fixture> fixtures = new ArrayList<>();

  @AfterEach
  void remove_placeholders() throws Exception {
    for (Fixture fixture : this.fixtures) {
      fixture.listener.stop();
      assertThat(fixture.notifications.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    Placeholders.removePlaceholders(Settings.IMP.MAIN.STRINGS.NOTIFY_JOIN);
  }

  @Test
  void notification_waits_for_first_connection_and_never_queries_during_initial_server_selection() throws Exception {
    final Fixture fixture = this.fixture();
    fixture.dispatch(new PlayerChooseInitialServerEvent(fixture.player, null));
    verifyNoInteractions(fixture.dao);
    verifyNoInteractions(fixture.manager);
    fixture.dispatch(new ServerPostConnectEvent(fixture.player, null));
    fixture.dispatch(new ServerPostConnectEvent(fixture.player, null));
    fixture.dispatch(new ServerPostConnectEvent(fixture.player, mock(RegisteredServer.class)));
    fixture.drainNotifications();
    verify(fixture.dao, times(1)).queryForId("fixture");
    verify(fixture.manager, times(1)).broadcastMessage(fixture.social, "Joined 127.0.0.1", List.of());
  }

  @Test
  void disabled_notifications_and_inactive_connections_never_query_the_database() throws Exception {
    final Fixture fixture = this.fixture();
    Settings.IMP.MAIN.ENABLE_NOTIFY = false;
    fixture.dispatch(new ServerPostConnectEvent(fixture.player, null));
    Settings.IMP.MAIN.ENABLE_NOTIFY = true;
    fixture.active.set(false);
    fixture.dispatch(new ServerPostConnectEvent(fixture.player, null));
    verifyNoInteractions(fixture.dao);
    verifyNoInteractions(fixture.manager);
  }

  @Test
  void disconnect_during_database_read_prevents_a_late_join_notification() throws Exception {
    final Fixture fixture = this.fixture();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    when(fixture.dao.queryForId("fixture")).thenAnswer(invocation -> {
      entered.countDown();
      assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
      return fixture.social;
    });
    try {
      fixture.listener.onServerConnected(new ServerPostConnectEvent(fixture.player, null));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      final DisconnectEvent disconnected = mock(DisconnectEvent.class);
      when(disconnected.getPlayer()).thenReturn(fixture.player);
      fixture.listener.onPlayerLeave(disconnected);
      release.countDown();
      fixture.drainNotifications();
      verifyNoInteractions(fixture.manager);
    } finally {
      release.countDown();
    }
  }

  @Test
  void blocked_optional_read_does_not_block_backend_connection_event() throws Exception {
    final Fixture fixture = this.fixture();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    when(fixture.dao.queryForId("fixture")).thenAnswer(invocation -> {
      entered.countDown();
      assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
      return fixture.social;
    });
    try {
      fixture.dispatch(new ServerPostConnectEvent(fixture.player, null));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      // The event has already returned although the optional database call is still pending.
      fixture.dispatch(new PlayerChooseInitialServerEvent(fixture.player, null));
      verifyNoInteractions(fixture.manager);
      release.countDown();
      fixture.drainNotifications();
      verify(fixture.manager).broadcastMessage(fixture.social, "Joined 127.0.0.1", List.of());
    } finally {
      release.countDown();
    }
  }

  @Test
  void reload_stop_discards_a_late_database_result() throws Exception {
    final Fixture fixture = this.fixture();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    when(fixture.dao.queryForId("fixture")).thenAnswer(invocation -> {
      entered.countDown();
      // A database driver may finish even after interruption.
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return fixture.social;
    });
    try {
      fixture.listener.onServerConnected(new ServerPostConnectEvent(fixture.player, null));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      fixture.listener.stop();
      release.countDown();
      assertThat(fixture.notifications.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      verifyNoInteractions(fixture.manager);
    } finally {
      release.countDown();
    }
  }

  private Fixture fixture() throws Exception {
    final Fixture fixture = new Fixture();
    this.fixtures.add(fixture);
    return fixture;
  }

  private static final class Fixture {

    private final ExecutorService notifications = Executors.newSingleThreadExecutor();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Player player = mock(Player.class);
    private final SocialManager manager = mock(SocialManager.class);
    private final SocialPlayer social;
    private final Dao<SocialPlayer, String> dao;
    private final LimboAuthListener listener;

    @SuppressWarnings("unchecked")
    private Fixture() throws Exception {
      Settings.IMP.MAIN = new Settings.MAIN();
      Settings.IMP.MAIN.STRINGS = new Settings.MAIN.STRINGS();
      Settings.IMP.MAIN.ENABLE_NOTIFY = true;
      Settings.IMP.MAIN.STRINGS.NOTIFY_JOIN = "Joined {IP}";
      Placeholders.addPlaceholders(Settings.IMP.MAIN.STRINGS.NOTIFY_JOIN, "{IP}", "{LOCATION}");
      this.dao = mock(Dao.class);
      this.social = new SocialPlayer("fixture");
      this.social.setNotifyEnabled(true);
      this.social.setTelegramID(123L);
      when(this.dao.queryForId("fixture")).thenReturn(this.social);
      when(this.player.getUsername()).thenReturn("Fixture");
      when(this.player.isActive()).thenAnswer(invocation -> this.active.get());
      when(this.player.getCurrentServer()).thenReturn(Optional.empty());
      when(this.player.getRemoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25565));
      final Serializer serializer = mock(Serializer.class);
      when(serializer.deserialize(any(String.class))).thenReturn(Component.empty());
      try (final MockedStatic<Addon> addon = mockStatic(Addon.class)) {
        addon.when(Addon::getSerializer).thenReturn(serializer);
        this.listener = new LimboAuthListener(null, null, this.dao, this.manager, List.of(), null, this.notifications);
      }
      clearInvocations(this.manager);
    }

    private void drainNotifications() throws Exception {
      this.notifications.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private void dispatch(final Object event) throws Exception {
      for (Method method : LimboAuthListener.class.getDeclaredMethods()) {
        if (method.isAnnotationPresent(Subscribe.class) && method.getParameterCount() == 1
            && method.getParameterTypes()[0].isInstance(event)) {
          method.invoke(this.listener, event);
        }
      }
    }
  }
}
