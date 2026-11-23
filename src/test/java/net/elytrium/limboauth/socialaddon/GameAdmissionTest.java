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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.event.PostAuthorizationEvent;
import net.elytrium.limboauth.event.PostRegisterEvent;
import net.elytrium.limboauth.model.RegisteredPlayer;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GameAdmissionTest {

  private final Player player = mock(Player.class);
  private final LimboAuth auth = mock(LimboAuth.class);
  private final LimboFactory factory = mock(LimboFactory.class);
  private final Limbo authWorld = mock(Limbo.class);
  private final LimboPlayer waiting = mock(LimboPlayer.class);
  private final LimboPlayer password = mock(LimboPlayer.class);
  private final RegisteredServer lobby = mock(RegisteredServer.class);
  private final LoginLimboRegisterEvent login = new LoginLimboRegisterEvent(this.player);
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private final List<Boolean> credentials = new CopyOnWriteArrayList<>();
  private final AtomicReference<String> poll = new AtomicReference<>("pending");
  private final AtomicInteger releases = new AtomicInteger();
  private final CompletableFuture<Void> pollReceived = new CompletableFuture<>();
  private final CompletableFuture<Void> respond = new CompletableFuture<>();
  private volatile boolean holdPoll;
  private volatile boolean trusted;
  private HttpServer service;
  private GameIdentity identity;
  private GameAdmission gate;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void prepare() throws Exception {
    final UUID id = UUID.randomUUID();
    when(this.player.getUniqueId()).thenReturn(id);
    when(this.player.getRemoteAddress()).thenReturn(new InetSocketAddress("192.0.2.8", 25565));
    when(this.player.getUsername()).thenReturn("PlayerOne");
    when(this.player.isActive()).thenReturn(true);
    when(this.password.getProxyPlayer()).thenReturn(this.player);
    when(this.auth.getAuthServer()).thenReturn(this.authWorld);
    final var accounts = (Dao<RegisteredPlayer, String>) mock(Dao.class);
    final var socials = (Dao<SocialPlayer, String>) mock(Dao.class);
    when(this.auth.getPlayerDao()).thenReturn(accounts);
    when(accounts.queryForId("playerone")).thenReturn(new RegisteredPlayer("PlayerOne", id.toString(), "192.0.2.8"));
    this.service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
    this.service.createContext("/internal/identity/", this::answer);
    this.service.start();
    URI serviceUrl = URI.create("http://127.0.0.1:" + this.service.getAddress().getPort());
    this.identity = new GameIdentity(mock(Addon.class), this.auth, socials, this.factory, "fixture", serviceUrl,
        "https://islandia.test/account/", new InetSocketAddress("127.0.0.1", 0));
    this.gate = this.identity.admission();
    doAnswer(call -> {
      this.next();
      return null;
    }).when(this.factory).passLoginLimbo(this.player);
    doAnswer(call -> {
      LimboSessionHandler handler = call.getArgument(1);
      handler.onSpawn(this.authWorld, this.waiting);
      return null;
    }).when(this.authWorld).spawnPlayer(eq(this.player), any(LimboSessionHandler.class));
    doAnswer(call -> {
      this.next();
      return null;
    }).when(this.waiting).disconnect();
  }

  @AfterEach
  void close() {
    this.respond.complete(null);
    this.identity.close();
    this.service.stop(0);
  }

  @Test
  void new_ip_waits_in_the_auth_world_and_is_recorded_only_after_the_lobby_accepts() throws Exception {
    this.holdPoll = true;
    this.login.addOnJoinCallback(this::correctPassword);
    this.gate.queue(this.login);
    this.next();
    this.pollReceived.get(5, TimeUnit.SECONDS);
    assertThat(this.credentials).containsExactly(true);
    verify(this.authWorld).spawnPlayer(eq(this.player), any(LimboSessionHandler.class));
    assertThat(this.mayConnect()).isFalse();
    assertThat(this.releases.get()).isZero();
    this.gate.entered(new ServerPostConnectEvent(this.player, null));
    assertThat(this.calls).doesNotContain("finish");
    this.poll.set("approved");
    this.respond.complete(null);
    this.awaitRelease();
    assertThat(this.mayConnect()).isTrue();
    assertThat(this.calls).doesNotContain("finish");
    this.gate.entered(new ServerPostConnectEvent(this.player, null));
    this.gate.entered(new ServerPostConnectEvent(this.player, this.lobby));
    assertThat(this.calls).containsExactly("start", "poll", "poll", "finish");
  }

  @ParameterizedTest
  @ValueSource(strings = {"denied", "expired", "unavailable"})
  void rejection_expiry_and_unavailable_identity_disconnect_without_joining(String result) throws Exception {
    this.poll.set(result);
    this.login.addOnJoinCallback(this::correctPassword);
    this.gate.queue(this.login);
    this.next();
    await().untilAsserted(() -> verify(this.player).disconnect(any(Component.class)));
    assertThat(this.mayConnect()).isFalse();
    verify(this.waiting, never()).disconnect();
    assertThat(this.releases.get()).isZero();
    await().until(() -> this.calls.contains("cancel"));
    assertThat(this.calls).doesNotContain("finish");
  }

  @Test
  void cached_login_can_request_a_fresh_password_on_the_same_connection() throws Exception {
    this.poll.set("approved");
    this.gate.queue(this.login);
    this.next();
    await().untilAsserted(() -> verify(this.auth).authPlayer(this.player));
    verify(this.auth).removePlayerFromCache("PlayerOne");
    assertThat(this.credentials).containsExactly(false);
    assertThat(this.mayConnect()).isFalse();
    this.correctPassword();
    this.awaitRelease();
    assertThat(this.credentials).containsExactly(false, true);
    assertThat(this.mayConnect()).isTrue();
    verify(this.player, never()).disconnect(any(Component.class));
  }

  @Test
  void trusted_cached_login_skips_the_waiting_world() throws Exception {
    this.trusted = true;
    this.poll.set("approved");
    this.gate.queue(this.login);
    this.next();
    this.awaitRelease();
    assertThat(this.credentials).containsExactly(false);
    verify(this.auth, never()).authPlayer(this.player);
    verify(this.authWorld, never()).spawnPlayer(eq(this.player), any(LimboSessionHandler.class));
    assertThat(this.mayConnect()).isTrue();
    this.poll.set("expired");
    assertThat(this.mayConnect()).isFalse();
    assertThat(this.calls).doesNotContain("finish");
  }

  @Test
  void registration_counts_as_a_fresh_password() throws Exception {
    this.trusted = true;
    this.login.addOnJoinCallback(() -> {
      this.gate.registered(new PostRegisterEvent(ignored -> { }, this.password, null, "fixture"));
      this.next();
    });
    this.gate.queue(this.login);
    this.next();
    this.awaitRelease();
    assertThat(this.credentials).containsExactly(true);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void approval_after_disconnect_or_reload_does_not_release_the_queue(boolean reload) throws Exception {
    this.holdPoll = true;
    this.login.addOnJoinCallback(this::correctPassword);
    this.gate.queue(this.login);
    this.next();
    this.pollReceived.get(5, TimeUnit.SECONDS);
    if (reload) {
      this.gate.close();
    } else {
      this.gate.left(new DisconnectEvent(this.player, DisconnectEvent.LoginStatus.CANCELLED_BY_USER));
    }
    this.poll.set("approved");
    this.respond.complete(null);
    await().until(() -> this.calls.contains("cancel"));
    assertThat(this.mayConnect()).isFalse();
    this.gate.entered(new ServerPostConnectEvent(this.player, null));
    assertThat(this.releases.get()).isZero();
    assertThat(this.calls).doesNotContain("finish");
  }

  private void correctPassword() {
    this.gate.authorized(new PostAuthorizationEvent(ignored -> { }, this.password, null, "fixture"));
    this.next();
  }

  private void next() {
    Runnable callback = this.login.getOnJoinCallbacks().poll();
    if (callback == null) {
      this.releases.incrementAndGet();
    } else {
      callback.run();
    }
  }

  private void awaitRelease() {
    await().until(() -> this.releases.get() == 1);
  }

  private boolean mayConnect() throws Exception {
    var event = new ServerPreConnectEvent(this.player, this.lobby);
    EventTask task = this.gate.entering(event);
    if (task != null) {
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
      result.get(5, TimeUnit.SECONDS);
    }
    return event.getResult().isAllowed();
  }

  private void answer(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      String action = path.substring(path.lastIndexOf('/') + 1);
      if (!"groups".equals(action)) {
        this.calls.add(action);
      }
      JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      JsonObject response = new JsonObject();
      if ("start".equals(action)) {
        boolean fresh = body.get("passwordChecked").getAsBoolean();
        this.credentials.add(fresh);
        assertThat(body.get("ip").getAsString()).isEqualTo("192.0.2.8");
        response.addProperty("state", this.trusted ? "approved" : fresh ? "pending" : "password");
        response.addProperty("attempt", "fixture-attempt");
      } else if ("poll".equals(action)) {
        this.pollReceived.complete(null);
        if (this.holdPoll) {
          this.respond.orTimeout(8, TimeUnit.SECONDS).join();
        }
        String state = this.poll.get();
        if ("expired".equals(state) || "unavailable".equals(state)) {
          exchange.sendResponseHeaders("expired".equals(state) ? 409 : 503, -1);
          return;
        }
        response.addProperty("state", state);
      } else if (!"groups".equals(action)) {
        response.addProperty("state", "done");
      }
      byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }
}
