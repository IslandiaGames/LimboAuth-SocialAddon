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
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.event.ChangePasswordEvent;
import net.elytrium.limboauth.event.PreAuthorizationEvent;
import net.elytrium.limboauth.handler.AuthSessionHandler;
import net.elytrium.limboauth.model.RegisteredPlayer;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameIdentity implements AutoCloseable {

  private static final Gson JSON = new Gson();
  private static final Logger LOGGER = LoggerFactory.getLogger(GameIdentity.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private final Addon addon;
  private final LimboAuth auth;
  private final Dao<SocialPlayer, String> socials;
  private final String key;
  private final URI service;
  private final String accountUrl;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(4);
  private final ThreadPoolExecutor requests = new ThreadPoolExecutor(2, 6, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(24));
  private final GameAdmission admission;
  private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
  private final StaffGroupSync groups = new StaffGroupSync();
  private final HttpServer server;

  private record Binding(String nick, String field, long id, long until) { }

  GameIdentity(Addon addon, LimboAuth auth, Dao<SocialPlayer, String> socials, LimboFactory limbo, String key,
               URI service, String accountUrl, InetSocketAddress address) throws IOException {
    this.addon = addon;
    this.auth = auth;
    this.socials = socials;
    this.key = key;
    this.service = service;
    this.accountUrl = accountUrl;
    this.admission = new GameAdmission(this, auth, limbo, this.worker);
    this.server = HttpServer.create(address, 16);
    this.server.setExecutor(this.requests);
    this.server.createContext("/v1/", this::handle);
    this.server.start();
    this.worker.scheduleWithFixedDelay(this::syncGroups, 5, 30, TimeUnit.SECONDS);
  }

  static boolean enabled() {
    return Boolean.parseBoolean(System.getenv("ISLANDIA_IDENTITY_ENABLED"));
  }

  public static GameIdentity open(Addon addon, LimboAuth auth, Dao<SocialPlayer, String> socials) {
    if (!enabled()) {
      return null;
    }
    String key = System.getenv("ISLANDIA_IDENTITY_KEY");
    if (key == null || key.length() < 32) {
      throw new IllegalStateException("Missing identity service key");
    }
    try {
      var limbo = (LimboFactory) addon.getServer().getPluginManager().getPlugin("limboapi").flatMap(PluginContainer::getInstance).orElseThrow();
      return new GameIdentity(addon, auth, socials, limbo, key,
          URI.create(System.getenv().getOrDefault("ISLANDIA_IDENTITY_SERVICE", "http://islandia-staff:8080")),
          System.getenv().getOrDefault("ISLANDIA_PUBLIC_ORIGIN", "https://islandia.su") + "/account/",
          new InetSocketAddress(Integer.parseInt(System.getenv().getOrDefault("ISLANDIA_IDENTITY_PORT", "18187"))));
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot start identity service", failure);
    }
  }

  GameAdmission admission() {
    return this.admission;
  }

  int port() {
    return this.server.getAddress().getPort();
  }

  public String accountUrl() {
    return this.accountUrl;
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String bearer = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
      if (!"POST".equals(exchange.getRequestMethod()) || !MessageDigest.isEqual(bearer.getBytes(StandardCharsets.UTF_8),
          ("Bearer " + this.key).getBytes(StandardCharsets.UTF_8))) {
        exchange.sendResponseHeaders(403, -1);
        return;
      }
      byte[] data = exchange.getRequestBody().readNBytes(8193);
      if (data.length > 8192) {
        exchange.sendResponseHeaders(413, -1);
        return;
      }
      try {
        JsonObject body = JSON.fromJson(new String(data, StandardCharsets.UTF_8), JsonObject.class);
        Object result = this.execute(exchange.getRequestURI().getPath(), body);
        byte[] bytes = JSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      } catch (IllegalArgumentException failure) {
        exchange.sendResponseHeaders(422, -1);
      } catch (Exception failure) {
        LOGGER.warn("Identity bridge operation failed: {}", failure.getClass().getSimpleName());
        exchange.sendResponseHeaders(503, -1);
      }
    }
  }

  private Object execute(String path, JsonObject body) throws SQLException {
    if ("/v1/group".equals(path)) {
      return this.groups.exists(body.get("name").getAsString());
    }
    String nick = body.get("nickname").getAsString().toLowerCase(Locale.ROOT);
    if (!nick.matches("[a-z0-9_]{1,16}")) {
      throw new IllegalArgumentException();
    }
    var player = this.auth.getPlayerDao().queryForId(nick);
    if (player == null || player.getUuid() == null || player.getUuid().isBlank()) {
      if ("/v1/verify".equals(path) || "/v1/account".equals(path)) {
        return null;
      }
      throw new IllegalArgumentException();
    }
    var social = this.socials.queryForId(nick);
    if ("/v1/account".equals(path) || "/v1/verify".equals(path)) {
      if ("/v1/verify".equals(path)) {
        String pass = body.get("password").getAsString();
        String totp = player.getTotpToken();
        if (pass.isEmpty() || pass.length() > 128 || player.getHash().isEmpty() || social != null && social.isBlocked()
            || !AuthSessionHandler.checkPassword(pass, player, this.auth.getPlayerDao())
            || totp != null && !totp.isEmpty() && !AuthSessionHandler.TOTP_CODE_VERIFIER.isValidCode(totp, body.get("code").getAsString())) {
          return null;
        }
      }
      return Map.of("id", player.getUuid(), "nickname", player.getNickname(), "socialProtection", social != null && social.isTotpEnabled(),
          "hasSocial", hasSocial(social));
    }
    if (!player.getUuid().equalsIgnoreCase(body.get("id").getAsString())) {
      throw new IllegalArgumentException();
    }
    switch (path) {
      case "/v1/notify" -> {
        if (social != null) {
          this.addon.getSocialManager().broadcastMessage(social, body.get("message").getAsString());
        }
      }
      case "/v1/validate-password" -> this.validatePassword(body.get("password").getAsString());
      case "/v1/reset" -> {
        String password = body.get("password").getAsString();
        this.validatePassword(password);
        var update = this.auth.getPlayerDao().updateBuilder();
        update.updateColumnValue(RegisteredPlayer.HASH_FIELD, RegisteredPlayer.genHash(password));
        if (body.get("clearSocial").getAsBoolean()) {
          update.updateColumnValue(RegisteredPlayer.TOTP_TOKEN_FIELD, "");
        }
        update.where().eq(RegisteredPlayer.LOWERCASE_NICKNAME_FIELD, nick).and().eq(RegisteredPlayer.UUID_FIELD, player.getUuid());
        if (update.update() != 1) {
          throw new IllegalArgumentException();
        }
        this.revoke(nick);
        if (body.get("clearSocial").getAsBoolean() && social != null) {
          this.addon.getSocialManager().unregisterHook(social);
          this.socials.delete(social);
        }
      }
      case "/v1/revoke" -> this.revoke(nick);
      case "/v1/factor" -> {
        if (social != null) {
          var update = this.socials.updateBuilder();
          update.updateColumnValue(SocialPlayer.TOTP_ENABLED_FIELD, body.get("enabled").getAsBoolean());
          update.where().eq(SocialPlayer.LOWERCASE_NICKNAME_FIELD, nick);
          update.update();
        }
      }
      case "/v1/channels" -> {
        JsonObject channels = new JsonObject();
        for (var field : SocialPlayer.DatabaseField.values()) {
          JsonObject channel = new JsonObject();
          channel.addProperty("bound", social != null && field.getIdFor(social) != null);
          channel.addProperty("contact", this.addon.getSocialManager().getPublicContact(field.name()).orElse(""));
          channels.add(field.name(), channel);
        }
        return channels;
      }
      case "/v1/channel" -> this.channel(nick, body, social);
      default -> throw new IllegalArgumentException();
    }
    return true;
  }

  private void validatePassword(String password) {
    var config = net.elytrium.limboauth.Settings.IMP.MAIN;
    if (password.length() < config.MIN_PASSWORD_LENGTH || password.getBytes(StandardCharsets.UTF_8).length > config.MAX_PASSWORD_LENGTH
        || password.chars().anyMatch(Character::isWhitespace) || config.CHECK_PASSWORD_STRENGTH && this.auth.getUnsafePasswords().contains(password)) {
      throw new IllegalArgumentException();
    }
  }

  private synchronized void channel(String nick, JsonObject body, SocialPlayer social) throws SQLException {
    String raw = body.get("token").getAsString();
    if (!raw.isEmpty()) {
      Binding binding = this.bindings.get(raw);
      if (binding == null || binding.until() <= System.currentTimeMillis() || !nick.equals(binding.nick())
          || !this.socials.queryForEq(binding.field(), binding.id()).isEmpty()
          || social != null && SocialPlayer.DatabaseField.valueOf(binding.field()).getIdFor(social) != null) {
        throw new IllegalArgumentException();
      }
      this.addon.linkSocial(nick, binding.field(), binding.id());
      this.addon.getSocialManager().registerHook(binding.field(), binding.id());
      this.bindings.remove(raw);
    } else {
      var field = SocialPlayer.DatabaseField.valueOf(body.get("field").getAsString());
      if (social == null || field.getIdFor(social) == null) {
        throw new IllegalArgumentException();
      }
      var update = this.socials.updateBuilder();
      update.updateColumnValue(field.name(), null);
      update.where().eq(SocialPlayer.LOWERCASE_NICKNAME_FIELD, nick).and().eq(field.name(), field.getIdFor(social));
      if (update.update() != 1) {
        throw new IllegalArgumentException();
      }
      this.addon.getSocialManager().unregisterHook(field.name(), social);
    }
  }

  public synchronized void inviteChannel(String nickname, String field, long id) {
    this.bindings.values().removeIf(value -> value.until() <= System.currentTimeMillis() || value.field().equals(field) && value.id() == id);
    if (!nickname.matches("[a-zA-Z0-9_]{1,16}") || this.bindings.size() >= 2048) {
      this.addon.getSocialManager().broadcastMessage(field, id, "Отправьте команду привязки со своим игровым ником.");
      return;
    }
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    this.bindings.put(raw, new Binding(nickname.toLowerCase(Locale.ROOT), field, id, System.currentTimeMillis() + 300000));
    this.addon.getSocialManager().broadcastMessage(field, id,
        "Подтвердите привязку к " + nickname + " в течение 5 минут: " + this.accountUrl + "#social=" + raw);
  }

  private void revoke(String nick) {
    this.auth.removePlayerFromCache(nick);
    this.addon.getServer().getPlayer(nick).ifPresent(player -> player.disconnect(Component.text("Войдите в Islandia ещё раз.")));
  }

  private static boolean hasSocial(SocialPlayer social) {
    return social != null && (social.getTelegramID() != null || social.getVkID() != null || social.getDiscordID() != null);
  }

  @Subscribe
  public void loginHelp(PreAuthorizationEvent event) {
    Player player = event.getPlayer();
    player.sendMessage(Component.text("Забыли пароль? Восстановить доступ")
        .clickEvent(ClickEvent.openUrl(this.accountUrl + "?forgot=1&nickname=" + player.getUsername())));
  }

  JsonObject start(Player player, boolean passwordChecked) {
    String nick = player.getUsername().toLowerCase(Locale.ROOT);
    try {
      var account = this.auth.getPlayerDao().queryForId(nick);
      if (account == null || !player.getUniqueId().toString().equalsIgnoreCase(account.getUuid())) {
        throw new IllegalStateException("Unknown game account");
      }
      var social = this.socials.queryForId(nick);
      if (social != null && social.isBlocked()) {
        JsonObject blocked = new JsonObject();
        blocked.addProperty("state", "blocked");
        return blocked;
      }
      return this.post("start", Map.of("id", player.getUniqueId(), "nickname", player.getUsername(),
          "ip", player.getRemoteAddress().getAddress().getHostAddress(), "protectedAccount", social != null && social.isTotpEnabled(),
          "hasSocial", hasSocial(social), "passwordChecked", passwordChecked));
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot load social protection", failure);
    }
  }

  @Subscribe
  public void passwordChanged(ChangePasswordEvent event) {
    var id = event.getPlayerInfo().getUuid();
    if (id != null && !id.isBlank()) {
      this.post("changed", Map.of("id", id));
    }
  }

  @Subscribe(order = PostOrder.FIRST)
  public void command(CommandExecuteEvent event) {
    if (event.getCommandSource() instanceof Player player) {
      String label = event.getCommand().split(" ", 2)[0].toLowerCase(Locale.ROOT);
      if (List.of("changepassword", "changepass", "cp", "forgotpassword", "forgot").contains(label)) {
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(Component.text("Пароль игрового аккаунта: " + this.accountUrl + (label.startsWith("forgot") ? "?forgot=1" : "")));
      }
    }
  }

  JsonObject post(String action, Object payload) {
    HttpRequest request = HttpRequest.newBuilder(this.service.resolve("/internal/identity/" + action))
        .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + this.key).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(payload))).build();
    try {
      var result = this.client.send(request, HttpResponse.BodyHandlers.ofString());
      if (result.statusCode() == 409 && "poll".equals(action)) {
        JsonObject expired = new JsonObject();
        expired.addProperty("state", "expired");
        return expired;
      }
      if (result.statusCode() / 100 != 2) {
        throw new IllegalStateException("Identity service unavailable");
      }
      return result.body().isBlank() ? new JsonObject() : JSON.fromJson(result.body(), JsonObject.class);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Identity request interrupted", interrupted);
    } catch (IOException failure) {
      throw new IllegalStateException("Identity service unavailable", failure);
    }
  }

  private void syncGroups() {
    try {
      this.groups.apply(this.post("groups", Map.of()));
    } catch (RuntimeException failure) {
      LOGGER.warn("Staff group synchronization failed: {}", failure.getClass().getSimpleName());
    }
  }

  @Override
  public void close() {
    this.admission.close();
    this.server.stop(0);
    this.worker.shutdown();
    this.requests.shutdownNow();
  }
}
