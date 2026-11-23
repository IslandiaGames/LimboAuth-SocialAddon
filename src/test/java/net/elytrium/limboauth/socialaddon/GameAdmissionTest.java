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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.event.PostAuthorizationEvent;
import net.elytrium.limboauth.model.RegisteredPlayer;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import org.junit.jupiter.api.Test;

class GameAdmissionTest {

  @Test
  @SuppressWarnings("unchecked")
  void approved_login_commits_only_after_backend_connection_and_abandoned_reconnect_cancels() throws Exception {
    final BlockingQueue<String> calls = new LinkedBlockingQueue<>();
    final var start = new AtomicReference<JsonObject>();
    var service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
    service.createContext("/internal/identity/", request -> {
      try (request) {
        String path = request.getRequestURI().getPath();
        String action = path.substring(path.lastIndexOf('/') + 1);
        JsonObject payload = JsonParser.parseString(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        if ("start".equals(action)) {
          start.set(payload);
        }
        String state = switch (action) {
          case "start" -> "pending";
          case "poll" -> "approved";
          default -> "done";
        };
        JsonObject response = new JsonObject();
        response.addProperty("state", state);
        response.addProperty("attempt", "test-attempt");
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        calls.add(action);
        request.sendResponseHeaders(200, bytes.length);
        request.getResponseBody().write(bytes);
      }
    });
    service.start();
    try {
      final var auth = mock(LimboAuth.class);
      final var accounts = (Dao<RegisteredPlayer, String>) mock(Dao.class);
      when(auth.getPlayerDao()).thenReturn(accounts);
      final var id = UUID.randomUUID();
      when(accounts.queryForId("playerone")).thenReturn(new RegisteredPlayer("PlayerOne", id.toString(), "192.0.2.8"));
      var socials = (Dao<SocialPlayer, String>) mock(Dao.class);
      URI address = URI.create("http://127.0.0.1:" + service.getAddress().getPort());
      try (var identity = new GameIdentity(mock(Addon.class), auth, socials, "fixture", address, "https://islandia.test/account/",
          new InetSocketAddress("127.0.0.1", 0))) {
        Player player = mock(Player.class);
        when(player.isActive()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn("PlayerOne");
        when(player.getRemoteAddress()).thenReturn(new InetSocketAddress("192.0.2.8", 25565));
        var limbo = mock(LimboPlayer.class);
        when(limbo.getProxyPlayer()).thenReturn(player);
        var authorized = mock(PostAuthorizationEvent.class);
        when(authorized.getPlayer()).thenReturn(limbo);
        identity.authorized(authorized);

        final var backend = mock(RegisteredServer.class);
        var event = new ServerPreConnectEvent(player, backend);
        await(identity.entering(event));
        assertThat(start.get().get("passwordChecked").getAsBoolean()).isTrue();
        assertThat(start.get().get("ip").getAsString()).isEqualTo("192.0.2.8");
        assertThat(event.getResult().isAllowed()).isTrue();
        assertThat(calls).containsExactly("start", "poll");
        identity.entered(new ServerPostConnectEvent(player, null));
        assertThat(calls).containsExactly("start", "poll", "finish");

        calls.clear();
        await(identity.entering(new ServerPreConnectEvent(player, backend)));
        assertThat(start.get().get("passwordChecked").getAsBoolean()).isFalse();
        var exit = mock(DisconnectEvent.class);
        when(exit.getPlayer()).thenReturn(player);
        identity.left(exit);
        identity.entered(new ServerPostConnectEvent(player, null));
        assertThat(calls.poll(3, TimeUnit.SECONDS)).isEqualTo("start");
        assertThat(calls.poll(3, TimeUnit.SECONDS)).isEqualTo("poll");
        assertThat(calls.poll(3, TimeUnit.SECONDS)).isEqualTo("cancel");
        assertThat(calls).isEmpty();
        verify(auth).removePlayerFromCache("PlayerOne");
      }
    } finally {
      service.stop(0);
    }
  }

  private static void await(EventTask task) throws Exception {
    CompletableFuture<Void> result = new CompletableFuture<>();
    task.execute(new Continuation() {
      @Override
      public void resume() {
        result.complete(null);
      }

      @Override
      public void resumeWithException(Throwable failure) {
        result.completeExceptionally(failure);
      }
    });
    result.get(10, TimeUnit.SECONDS);
  }
}
