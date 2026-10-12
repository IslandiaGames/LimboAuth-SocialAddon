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
import java.util.Objects;
import net.elytrium.commons.kyori.serialization.Serializer;
import net.elytrium.commons.kyori.serialization.Serializers;
import net.elytrium.limboauth.socialaddon.command.ValidateLinkCommand;
import net.elytrium.limboauth.socialaddon.listener.SocialLinkListener;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

class SocialLinkTest {

  @TempDir
  Path directory;

  @Test
  void messenger_aliases_open_their_configured_bot_without_confirming_an_account() throws Exception {
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
    assertThat(urls(values.get(2))).containsExactly(settings.MAIN.VK.CONTACT);
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
      assertThat(urls(menu.announcement(player).orElseThrow())).containsExactly(settings.MAIN.VK.CONTACT);
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
    SocialLinkListener listener = new SocialLinkListener(dao, this.menu(settings), mock(Logger.class));
    Player player = player();
    ServerPostConnectEvent connected = new ServerPostConnectEvent(player, null);
    listener.onServerConnected(connected);
    listener.onServerConnected(connected);
    listener.onServerConnected(new ServerPostConnectEvent(player, mock(RegisteredServer.class)));
    verify(player).sendMessage(any(Component.class));
    verify(dao).queryForId("fixture");
    DisconnectEvent disconnect = mock(DisconnectEvent.class);
    when(disconnect.getPlayer()).thenReturn(player);
    listener.onDisconnect(disconnect);
    listener.onServerConnected(connected);
    verify(player, times(2)).sendMessage(any(Component.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void stored_rows_without_links_are_invited_but_linked_and_disconnected_players_are_not() throws Exception {
    Settings settings = this.settings();
    Dao<SocialPlayer, String> dao = mock(Dao.class);
    Settings.IMP.MAIN = settings.MAIN;
    SocialPlayer linked = new SocialPlayer("fixture");
    linked.setTelegramID(12L);
    when(dao.queryForId("fixture")).thenReturn(linked);
    SocialLinkListener listener = new SocialLinkListener(dao, this.menu(settings), mock(Logger.class));
    Player player = player();
    listener.onServerConnected(new ServerPostConnectEvent(player, null));
    verify(player, never()).sendMessage(any(Component.class));
    Player disconnected = player();
    when(disconnected.isActive()).thenReturn(false);
    listener.onServerConnected(new ServerPostConnectEvent(disconnected, null));
    verify(disconnected, never()).sendMessage(any(Component.class));
    Player unlinked = player();
    when(dao.queryForId("fixture")).thenReturn(new SocialPlayer("fixture"));
    listener.onServerConnected(new ServerPostConnectEvent(unlinked, null));
    verify(unlinked).sendMessage(any(Component.class));
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
    return new SocialLinkMenu(settings.MAIN, serializer, mock(Logger.class));
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
}
