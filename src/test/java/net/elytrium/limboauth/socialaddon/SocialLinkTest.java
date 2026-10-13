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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.elytrium.commons.kyori.serialization.Serializer;
import net.elytrium.commons.kyori.serialization.Serializers;
import net.elytrium.limboauth.socialaddon.command.ValidateLinkCommand;
import net.elytrium.limboauth.socialaddon.listener.SocialLinkListener;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

class SocialLinkTest {

  @TempDir
  Path directory;

  @Test
  void service_aliases_open_their_configured_bot_without_confirming_an_account() throws Exception {
    Settings settings = this.settings();
    SocialLinkMenu menu = this.menu(settings);
    Player player = player();
    Addon addon = mock(Addon.class);
    ValidateLinkCommand command = new ValidateLinkCommand(addon, menu);
    for (String alias : List.of("addtg", "addds", "addvk", "addsocial")) {
      SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
      when(invocation.source()).thenReturn(player);
      when(invocation.alias()).thenReturn(alias);
      when(invocation.arguments()).thenReturn(new String[0]);
      command.execute(invocation);
    }
    ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
    verify(player, times(4)).sendMessage(messages.capture());
    List<Component> values = messages.getAllValues();
    assertThat(urls(values.get(0))).containsExactly(settings.MAIN.TELEGRAM.CONTACT);
    assertThat(urls(values.get(1))).containsExactly(settings.MAIN.DISCORD.CONTACT);
    assertThat(urls(values.get(2))).containsExactly("https://vk.me/test_bot");
    assertThat(urls(values.get(3))).hasSize(3);
    verify(addon, never()).linkSocial(any(), any(), any());
  }

  @Test
  void disabled_or_invalid_contacts_never_produce_links_on_modern_or_legacy_clients() throws Exception {
    Settings settings = this.settings();
    settings.MAIN.TELEGRAM.ENABLED = false;
    settings.MAIN.DISCORD.CONTACT = "javascript:alert(1)";
    SocialLinkMenu menu = this.menu(settings);
    Player player = player();
    for (ProtocolVersion version : List.of(ProtocolVersion.MINECRAFT_1_8, ProtocolVersion.MINECRAFT_1_16)) {
      when(player.getProtocolVersion()).thenReturn(version);
      assertThat(urls(menu.announcement(player).orElseThrow())).containsExactly("https://vk.me/test_bot");
      assertThat(urls(menu.usage(player, "addtg"))).isEmpty();
      assertThat(urls(menu.usage(player, "addds"))).isEmpty();
    }
    settings.MAIN.VK.CONTACT = "https://secret@example.invalid/";
    assertThat(this.menu(settings).announcement(player)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void invitation_follows_first_successful_connection_and_allows_a_new_login_after_disconnect() throws Exception {
    Settings settings = this.settings();
    Dao<SocialPlayer, String> dao = mock(Dao.class);
    SocialLinkListener listener = new SocialLinkListener(dao, this.menu(settings), mock(Logger.class), System::nanoTime);
    Player player = player();
    ServerPostConnectEvent connected = new ServerPostConnectEvent(player, null);
    listener.onServerConnected(connected);
    listener.onServerConnected(connected);
    listener.onServerConnected(new ServerPostConnectEvent(player, mock(RegisteredServer.class)));
    verify(dao, never()).queryForId(any());
    listener.retryPending();
    listener.retryPending();
    verify(player).sendMessage(any(Component.class));
    verify(dao).queryForId("fixture");
    DisconnectEvent disconnect = mock(DisconnectEvent.class);
    when(disconnect.getPlayer()).thenReturn(player);
    listener.onDisconnect(disconnect);
    listener.onServerConnected(connected);
    listener.retryPending();
    verify(player, times(2)).sendMessage(any(Component.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void connection_event_returns_without_waiting_for_the_invitation_database() throws Exception {
    Settings settings = this.settings();
    Dao<SocialPlayer, String> dao = mock(Dao.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(dao.queryForId("fixture")).thenAnswer(invocation -> {
      entered.countDown();
      assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
      return null;
    });
    SocialLinkListener listener = new SocialLinkListener(dao, this.menu(settings), mock(Logger.class), () -> 0L);
    Player player = player();
    ServerPostConnectEvent connected = new ServerPostConnectEvent(player, null);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> connection = executor.submit(() -> listener.onServerConnected(connected));
      connection.get(2, TimeUnit.SECONDS);
      verify(dao, never()).queryForId(any());
      final Future<?> lookup = executor.submit(listener::retryPending);
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      executor.submit(() -> listener.onServerConnected(connected)).get(2, TimeUnit.SECONDS);
      listener.retryPending();
      verify(player, never()).sendMessage(any(Component.class));
      release.countDown();
      lookup.get(10, TimeUnit.SECONDS);
      listener.retryPending();
      verify(dao).queryForId("fixture");
      verify(player).sendMessage(any(Component.class));
    } finally {
      release.countDown();
      listener.stop();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void every_link_combination_is_invited_with_only_its_linked_services_marked() throws Exception {
    Settings settings = this.settings();
    Dao<SocialPlayer, String> dao = mock(Dao.class);
    Settings.IMP.MAIN = settings.MAIN;
    SocialLinkListener listener = new SocialLinkListener(dao, this.menu(settings), mock(Logger.class), System::nanoTime);
    for (int links = 0; links < 8; ++links) {
      SocialPlayer social = new SocialPlayer("fixture");
      social.setTelegramID((links & 1) == 0 ? null : 12L);
      social.setDiscordID((links & 2) == 0 ? null : 34L);
      social.setVkID((links & 4) == 0 ? null : 56L);
      when(dao.queryForId("fixture")).thenReturn(social);
      for (ProtocolVersion version : List.of(ProtocolVersion.MINECRAFT_1_8, ProtocolVersion.MINECRAFT_1_16)) {
        Player player = player();
        when(player.getProtocolVersion()).thenReturn(version);
        listener.onServerConnected(new ServerPostConnectEvent(player, null));
        listener.retryPending();
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(message.capture());
        assertThat(text(message.getValue())).contains(
            "[Telegram" + ((links & 1) == 0 ? "" : " ✓") + "]",
            "[Discord" + ((links & 2) == 0 ? "" : " ✓") + "]",
            "[VK" + ((links & 4) == 0 ? "" : " ✓") + "]");
        assertThat(urls(message.getValue())).hasSize(3);
      }
    }
    Player disconnected = player();
    when(disconnected.isActive()).thenReturn(false);
    listener.onServerConnected(new ServerPostConnectEvent(disconnected, null));
    verify(disconnected, never()).sendMessage(any(Component.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void blank_contacts_are_discovered_after_login_and_invitation_is_sent_once() throws Exception {
    Settings settings = this.settings();
    settings.MAIN.TELEGRAM.CONTACT = "";
    settings.MAIN.DISCORD.CONTACT = "";
    settings.MAIN.VK.CONTACT = "";
    Map<String, String> contacts = new ConcurrentHashMap<>();
    Serializer serializer = new Serializer(Objects.requireNonNull(Serializers.LEGACY_AMPERSAND.getSerializer()));
    SocialLinkMenu menu = new SocialLinkMenu(settings.MAIN, serializer, mock(Logger.class),
        field -> Optional.ofNullable(contacts.get(field)));
    Dao<SocialPlayer, String> dao = mock(Dao.class);
    AtomicLong clock = new AtomicLong();
    SocialLinkListener listener = new SocialLinkListener(dao, menu, mock(Logger.class), clock::get);
    Player player = player();
    listener.onServerConnected(new ServerPostConnectEvent(player, null));
    listener.retryPending();
    verify(player, never()).sendMessage(any(Component.class));
    verify(dao, never()).queryForId(any());
    assertThat(urls(menu.usage(player, "addtg"))).isEmpty();
    contacts.put(SocialPlayer.TELEGRAM_DB_FIELD, "https://t.me/discovered_bot");
    listener.retryPending();
    listener.retryPending();
    ArgumentCaptor<Component> announcement = ArgumentCaptor.forClass(Component.class);
    verify(player).sendMessage(announcement.capture());
    assertThat(urls(announcement.getValue())).containsExactly("https://t.me/discovered_bot");
    verify(dao).queryForId("fixture");
    contacts.put(SocialPlayer.DISCORD_DB_FIELD, "https://discord.com/users/987654321");
    contacts.put(SocialPlayer.VK_DB_FIELD, "https://vk.me/club123456");
    assertThat(urls(menu.usage(player, "addtg"))).containsExactly("https://t.me/discovered_bot");
    assertThat(urls(menu.usage(player, "addds"))).containsExactly("https://discord.com/users/987654321");
    assertThat(urls(menu.usage(player, "addvk"))).containsExactly("https://vk.me/club123456");
    listener.retryPending();
    verify(player).sendMessage(any(Component.class));
  }

  @Test
  void explicit_contacts_take_priority_and_disabled_services_are_not_discovered() throws Exception {
    Settings settings = this.settings();
    settings.MAIN.TELEGRAM.ENABLED = false;
    settings.MAIN.TELEGRAM.CONTACT = "";
    Serializer serializer = new Serializer(Objects.requireNonNull(Serializers.LEGACY_AMPERSAND.getSerializer()));
    SocialLinkMenu menu = new SocialLinkMenu(settings.MAIN, serializer, mock(Logger.class),
        ignored -> Optional.of("https://example.invalid/discovered"));
    assertThat(urls(menu.usage(player(), "addtg"))).isEmpty();
    assertThat(urls(menu.usage(player(), "addds"))).containsExactly(settings.MAIN.DISCORD.CONTACT);
    assertThat(urls(menu.usage(player(), "addvk"))).containsExactly("https://vk.me/test_bot");
  }

  @Test
  @SuppressWarnings("unchecked")
  void delayed_contacts_do_not_invite_after_disconnect_shutdown_or_timeout() throws Exception {
    for (String scenario : List.of("disconnect", "shutdown", "timeout")) {
      Settings settings = this.settings();
      Settings.IMP.MAIN = settings.MAIN;
      settings.MAIN.TELEGRAM.CONTACT = "";
      settings.MAIN.DISCORD.ENABLED = false;
      settings.MAIN.VK.ENABLED = false;
      Map<String, String> contacts = new ConcurrentHashMap<>();
      Serializer serializer = new Serializer(Objects.requireNonNull(Serializers.LEGACY_AMPERSAND.getSerializer()));
      SocialLinkMenu menu = new SocialLinkMenu(settings.MAIN, serializer, mock(Logger.class),
          field -> Optional.ofNullable(contacts.get(field)));
      Dao<SocialPlayer, String> dao = mock(Dao.class);
      AtomicLong clock = new AtomicLong();
      SocialLinkListener listener = new SocialLinkListener(dao, menu, mock(Logger.class), clock::get);
      Player player = player();
      listener.onServerConnected(new ServerPostConnectEvent(player, null));
      switch (scenario) {
        case "disconnect": {
          DisconnectEvent event = mock(DisconnectEvent.class);
          when(event.getPlayer()).thenReturn(player);
          listener.onDisconnect(event);
          break;
        }
        case "shutdown":
          listener.stop();
          break;
        default:
          clock.set(TimeUnit.MINUTES.toNanos(2));
          break;
      }
      contacts.put(SocialPlayer.TELEGRAM_DB_FIELD, "https://t.me/discovered_bot");
      listener.retryPending();
      listener.retryPending();
      verify(player, never()).sendMessage(any(Component.class));
      listener.stop();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void linking_while_waiting_for_a_contact_updates_the_pending_invitation() throws Exception {
    Settings settings = this.settings();
    Settings.IMP.MAIN = settings.MAIN;
    settings.MAIN.TELEGRAM.CONTACT = "";
    settings.MAIN.DISCORD.ENABLED = false;
    settings.MAIN.VK.ENABLED = false;
    Map<String, String> contacts = new ConcurrentHashMap<>();
    Serializer serializer = new Serializer(Objects.requireNonNull(Serializers.LEGACY_AMPERSAND.getSerializer()));
    SocialLinkMenu menu = new SocialLinkMenu(settings.MAIN, serializer, mock(Logger.class),
        field -> Optional.ofNullable(contacts.get(field)));
    Dao<SocialPlayer, String> dao = mock(Dao.class);
    SocialLinkListener listener = new SocialLinkListener(dao, menu, mock(Logger.class), () -> 0L);
    Player player = player();
    listener.onServerConnected(new ServerPostConnectEvent(player, null));
    SocialPlayer linked = new SocialPlayer("fixture");
    linked.setTelegramID(123L);
    when(dao.queryForId("fixture")).thenReturn(linked);
    contacts.put(SocialPlayer.TELEGRAM_DB_FIELD, "https://t.me/discovered_bot");
    listener.retryPending();
    listener.retryPending();
    ArgumentCaptor<Component> announcement = ArgumentCaptor.forClass(Component.class);
    verify(player).sendMessage(announcement.capture());
    assertThat(text(announcement.getValue())).contains("[Telegram ✓]").doesNotContain("Discord", "VK");
    verify(dao).queryForId("fixture");
  }

  @Test
  @SuppressWarnings("unchecked")
  void late_account_lookup_cannot_send_a_second_invitation_after_reconnection() throws Exception {
    Settings settings = this.settings();
    Settings.IMP.MAIN = settings.MAIN;
    Dao<SocialPlayer, String> dao = mock(Dao.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger queries = new AtomicInteger();
    SocialPlayer linked = new SocialPlayer("fixture");
    linked.setDiscordID(123L);
    when(dao.queryForId("fixture")).thenAnswer(invocation -> {
      if (queries.getAndIncrement() == 0) {
        entered.countDown();
        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
        return null;
      }
      return linked;
    });
    SocialLinkListener listener = new SocialLinkListener(dao, this.menu(settings), mock(Logger.class), () -> 0L);
    Player player = player();
    ServerPostConnectEvent connected = new ServerPostConnectEvent(player, null);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      listener.onServerConnected(connected);
      final Future<?> waiting = executor.submit(listener::retryPending);
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      listener.retryPending();
      listener.onServerConnected(connected);
      verify(player, never()).sendMessage(any(Component.class));
      DisconnectEvent disconnect = mock(DisconnectEvent.class);
      when(disconnect.getPlayer()).thenReturn(player);
      listener.onDisconnect(disconnect);
      listener.onServerConnected(connected);
      listener.retryPending();
      release.countDown();
      waiting.get(10, TimeUnit.SECONDS);
      ArgumentCaptor<Component> announcement = ArgumentCaptor.forClass(Component.class);
      verify(player).sendMessage(announcement.capture());
      assertThat(text(announcement.getValue())).contains("[Discord ✓]");
      verify(dao, times(2)).queryForId("fixture");
    } finally {
      release.countDown();
      listener.stop();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Settings settings() throws Exception {
    Path file = this.directory.resolve("config.yml");
    Files.writeString(file, """
        prefix: "&bIslandia &8>>&f"
        main:
          telegram:
            enabled: true
            contact: "https://t.me/islandia_test_bot"
            token: "test-only"
          discord:
            enabled: true
            contact: "https://discord.com/users/1234567890"
            token: "test-only"
          vk:
            enabled: true
            contact: "https://vk.ru/test_bot"
            token: "test-only"
          strings:
            link-announcement: "{PRFX} Link your account:"
        """);
    Settings settings = new Settings();
    settings.reload(file.toFile(), "Islandia");
    assertThat(settings.MAIN.TELEGRAM.ENABLED).isTrue();
    assertThat(settings.MAIN.DISCORD.ENABLED).isTrue();
    assertThat(settings.MAIN.VK.ENABLED).isTrue();
    return settings;
  }

  private SocialLinkMenu menu(Settings settings) {
    Serializer serializer = new Serializer(Objects.requireNonNull(Serializers.LEGACY_AMPERSAND.getSerializer()));
    return new SocialLinkMenu(settings.MAIN, serializer, mock(Logger.class), ignored -> Optional.empty());
  }

  private static Player player() {
    Player player = mock(Player.class);
    when(player.getUsername()).thenReturn("Fixture");
    when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_16);
    when(player.isActive()).thenReturn(true);
    return player;
  }

  private static List<String> urls(Component component) {
    List<String> result = new ArrayList<>();
    ClickEvent click = component.clickEvent();
    if (click != null && click.action() == ClickEvent.Action.OPEN_URL) {
      result.add(click.value());
    }
    for (Component child : component.children()) {
      result.addAll(urls(child));
    }
    return result;
  }

  private static String text(Component component) {
    StringBuilder result = new StringBuilder();
    if (component instanceof TextComponent text) {
      result.append(text.content());
    }
    for (Component child : component.children()) {
      result.append(text(child));
    }
    return result.toString();
  }
}
