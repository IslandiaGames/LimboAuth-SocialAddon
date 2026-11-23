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

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.event.PostAuthorizationEvent;
import net.elytrium.limboauth.event.PostEvent;
import net.elytrium.limboauth.event.PostRegisterEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameAdmission {

  private static final Logger LOGGER = LoggerFactory.getLogger(GameAdmission.class);
  private static final String UNAVAILABLE = "Проверка входа недоступна. Попробуйте войти снова.";
  private final GameIdentity identity;
  private final LimboAuth auth;
  private final LimboFactory limbo;
  private final ScheduledExecutorService worker;
  private final Map<Player, Attempt> attempts = new ConcurrentHashMap<>();
  private volatile boolean closed;

  GameAdmission(GameIdentity identity, LimboAuth auth, LimboFactory limbo, ScheduledExecutorService worker) {
    this.identity = identity;
    this.auth = auth;
    this.limbo = limbo;
    this.worker = worker;
  }

  @Subscribe(order = PostOrder.LAST)
  public void queue(LoginLimboRegisterEvent event) {
    Player player = event.getPlayer();
    var attempt = new Attempt(player);
    this.attempts.put(player, attempt);
    if (this.closed) {
      attempt.reject(UNAVAILABLE);
      return;
    }
    // LimboAuth queues its normal password/TOTP callback first. A cached login can still require a fresh password.
    event.addOnJoinCallback(attempt::checkCache);
    event.addOnJoinCallback(attempt::afterPassword);
  }

  @Subscribe
  public void authorized(PostAuthorizationEvent event) {
    this.passwordChecked(event);
  }

  @Subscribe
  public void registered(PostRegisterEvent event) {
    this.passwordChecked(event);
  }

  private void passwordChecked(PostEvent event) {
    var attempt = this.attempts.get(event.getPlayer().getProxyPlayer());
    if (attempt != null && event.getPassword() != null && !event.getPassword().isEmpty()) {
      attempt.passwordChecked = true;
    }
  }

  @Subscribe(order = PostOrder.LAST)
  public EventTask entering(ServerPreConnectEvent event) {
    if (event.getPreviousServer() != null || !event.getResult().isAllowed()) {
      return null;
    }
    var attempt = this.attempts.get(event.getPlayer());
    if (attempt == null || !attempt.approved || !attempt.active()) {
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return null;
    }
    return EventTask.resumeWhenComplete(CompletableFuture.runAsync(() -> {
      try {
        if ("approved".equals(attempt.request("poll")) && attempt.active()) {
          return;
        }
      } catch (RuntimeException failure) {
        LOGGER.warn("Could not check game login before connecting: {}", failure.getClass().getSimpleName());
      }
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      attempt.reject("Запрос на вход больше не действует. Подключитесь снова.");
    }, this.worker));
  }

  @Subscribe
  public void entered(ServerPostConnectEvent event) {
    Player player = event.getPlayer();
    if (event.getPreviousServer() == null) {
      var attempt = this.attempts.get(player);
      if (attempt != null && attempt.approved && player.isActive() && this.attempts.remove(player, attempt)) {
        try {
          if ("done".equals(attempt.request("finish"))) {
            return;
          }
        } catch (RuntimeException failure) {
          LOGGER.warn("Could not finish game login: {}", failure.getClass().getSimpleName());
        }
        this.auth.removePlayerFromCache(player.getUsername());
        player.disconnect(Component.text(UNAVAILABLE));
      }
    }
  }

  @Subscribe
  public void left(DisconnectEvent event) {
    var attempt = this.attempts.get(event.getPlayer());
    if (attempt != null) {
      attempt.abandon();
    }
  }

  void close() {
    this.closed = true;
    this.attempts.values().forEach(attempt -> attempt.reject("Переподключитесь к Islandia."));
  }

  private final class Attempt implements LimboSessionHandler {

    private final Player player;
    private final String ip;
    private volatile boolean passwordChecked;
    private volatile boolean approved;
    private volatile String token;

    private Attempt(Player player) {
      this.player = player;
      this.ip = player.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean active() {
      return !GameAdmission.this.closed && this.player.isActive() && GameAdmission.this.attempts.get(this.player) == this;
    }

    private void checkCache() {
      if (this.passwordChecked) {
        this.advance();
      } else {
        this.start(false);
      }
    }

    private void afterPassword() {
      if (this.token != null) {
        this.admit();
      } else if (this.passwordChecked) {
        this.start(true);
      } else {
        this.reject("Введите игровой пароль для подтверждения входа.");
      }
    }

    private void start(boolean fresh) {
      GameAdmission.this.worker.execute(() -> {
        if (!this.active()) {
          return;
        }
        try {
          var result = GameAdmission.this.identity.start(this.player, fresh);
          String state = result.get("state").getAsString();
          if ("approved".equals(state) || "pending".equals(state)) {
            this.token = result.get("attempt").getAsString();
          }
          if (!this.active()) {
            this.abandon();
            this.cancel();
            return;
          }
          switch (state) {
            case "approved" -> {
              if (fresh) {
                this.admit();
              } else {
                this.advance();
              }
            }
            case "password" -> {
              if (fresh) {
                this.reject("Доступ к аккаунту изменился. Подключитесь снова.");
              } else {
                GameAdmission.this.auth.removePlayerFromCache(this.player.getUsername());
                GameAdmission.this.auth.authPlayer(this.player);
              }
            }
            case "pending" -> {
              if (fresh) {
                GameAdmission.this.auth.getAuthServer().spawnPlayer(this.player, this);
              } else {
                this.reject("Введите игровой пароль для подтверждения входа.");
              }
            }
            case "blocked" -> this.reject("Аккаунт заблокирован.");
            default -> this.reject(UNAVAILABLE);
          }
        } catch (RuntimeException failure) {
          this.reject(UNAVAILABLE);
        }
      });
    }

    private void advance() {
      if (this.active()) {
        GameAdmission.this.limbo.passLoginLimbo(this.player);
      }
    }

    private void admit() {
      this.approved = true;
      this.advance();
    }

    @Override
    public void onSpawn(Limbo server, LimboPlayer waiting) {
      if (this.active()) {
        waiting.disableFalling();
        this.player.sendMessage(Component.text("Подтвердите вход в почте или привязанном мессенджере. Запрос действует 5 минут."));
        this.player.sendMessage(Component.text("Отправить повторно через минуту")
            .clickEvent(ClickEvent.openUrl(GameAdmission.this.identity.accountUrl() + "#resend=" + this.token + "&ip=" + this.ip)));
        GameAdmission.this.worker.execute(() -> this.poll(waiting));
      }
    }

    @Override
    public void onDisconnect() {
      if (!this.approved || !this.player.isActive()) {
        this.abandon();
      }
    }

    private void poll(LimboPlayer waiting) {
      if (!this.active()) {
        return;
      }
      try {
        String state = this.request("poll");
        if (this.active()) {
          switch (state) {
            case "pending" -> GameAdmission.this.worker.schedule(() -> this.poll(waiting), 2, TimeUnit.SECONDS);
            case "approved" -> {
              this.approved = true;
              this.player.sendMessage(Component.text("Вход подтверждён."));
              waiting.disconnect();
            }
            case "denied" -> this.reject("Вход отклонён. При необходимости смените пароль в аккаунте Islandia.");
            case "expired" -> this.reject("Запрос на вход истёк. Подключитесь снова.");
            default -> this.reject(UNAVAILABLE);
          }
        }
      } catch (RuntimeException failure) {
        this.reject(UNAVAILABLE);
      }
    }

    private String request(String action) {
      return GameAdmission.this.identity.post(action, Map.of("token", this.token, "ip", this.ip)).get("state").getAsString();
    }

    private void reject(String message) {
      if (this.abandon()) {
        this.player.disconnect(Component.text(message));
      }
    }

    private boolean abandon() {
      if (!GameAdmission.this.attempts.remove(this.player, this)) {
        return false;
      }
      GameAdmission.this.auth.removePlayerFromCache(this.player.getUsername());
      if (this.token != null) {
        GameAdmission.this.worker.execute(this::cancel);
      }
      return true;
    }

    private void cancel() {
      String abandoned;
      synchronized (this) {
        abandoned = this.token;
        this.token = null;
      }
      if (abandoned != null) {
        try {
          GameAdmission.this.identity.post("cancel", Map.of("token", abandoned, "ip", this.ip));
        } catch (RuntimeException failure) {
          LOGGER.warn("Could not cancel game login: {}", failure.getClass().getSimpleName());
        }
      }
    }
  }
}
