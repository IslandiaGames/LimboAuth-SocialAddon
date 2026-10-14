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

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.elytrium.commons.config.Placeholders;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.event.AuthUnregisterEvent;
import net.elytrium.limboauth.event.PostAuthorizationEvent;
import net.elytrium.limboauth.event.PreAuthorizationEvent;
import net.elytrium.limboauth.event.TaskEvent;
import net.elytrium.limboauth.socialaddon.Addon;
import net.elytrium.limboauth.socialaddon.Settings;
import net.elytrium.limboauth.socialaddon.SocialManager;
import net.elytrium.limboauth.socialaddon.handler.PreLoginLimboSessionHandler;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.AbstractSocial;
import net.elytrium.limboauth.socialaddon.utils.GeoIp;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LimboAuthListener {

  private static final String ASK_NO_BTN = "ask_no";
  private static final String ASK_YES_BTN = "ask_yes";
  private static final Logger LOGGER = LoggerFactory.getLogger(LimboAuthListener.class);

  private final Component blockedAccount = Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.BLOCK_KICK_MESSAGE);
  private final Component askedKick = Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_KICK_MESSAGE);
  private final Component socialUnavailable = Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.SOCIAL_UNAVAILABLE);
  private final Component askedValidate = Addon.getSerializer().deserialize(Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_VALIDATE_GAME);

  private final Addon addon;
  private final LimboAuth plugin;
  private final Dao<SocialPlayer, String> socialPlayerDao;
  private final SocialManager socialManager;

  private final List<List<AbstractSocial.ButtonItem>> yesNoButtons;
  private final List<List<AbstractSocial.ButtonItem>> keyboard;
  private final Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
  private final Map<Player, Object> joinNotifications = new ConcurrentHashMap<>();
  private final ExecutorService notifications;
  private volatile boolean stopped;

  private final GeoIp geoIp;
  private final boolean auth2faWithoutPassword = Settings.IMP.MAIN.AUTH_2FA_WITHOUT_PASSWORD;

  public LimboAuthListener(final Addon addon, final LimboAuth plugin, final Dao<SocialPlayer, String> socialPlayerDao,
                           final SocialManager socialManager, final List<List<AbstractSocial.ButtonItem>> keyboard, final GeoIp geoIp) {
    this(addon, plugin, socialPlayerDao, socialManager, keyboard, geoIp,
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
          final Thread thread = new Thread(task, "social-login-notifications");
          thread.setDaemon(true);
          return thread;
        }));
  }

  LimboAuthListener(final Addon addon, final LimboAuth plugin, final Dao<SocialPlayer, String> socialPlayerDao,
                    final SocialManager socialManager, final List<List<AbstractSocial.ButtonItem>> keyboard,
                    final GeoIp geoIp, final ExecutorService notifications) {
    this.addon = addon;
    this.plugin = plugin;
    this.socialPlayerDao = socialPlayerDao;
    this.socialManager = socialManager;
    this.keyboard = keyboard;
    this.geoIp = geoIp;
    this.notifications = notifications;
    if (Settings.IMP.MAIN.REVERSE_YES_NO_BUTTONS) {
      this.yesNoButtons = List.of(
          List.of(
              new AbstractSocial.ButtonItem(ASK_YES_BTN, Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_YES, AbstractSocial.ButtonItem.Color.GREEN),
              new AbstractSocial.ButtonItem(ASK_NO_BTN, Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_NO, AbstractSocial.ButtonItem.Color.RED)
          )
      );
    } else {
      this.yesNoButtons = List.of(
          List.of(
              new AbstractSocial.ButtonItem(ASK_NO_BTN, Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_NO, AbstractSocial.ButtonItem.Color.RED),
              new AbstractSocial.ButtonItem(ASK_YES_BTN, Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_YES, AbstractSocial.ButtonItem.Color.GREEN)
          )
      );
    }

    this.socialManager.registerKeyboard(this.yesNoButtons);
    this.socialManager.removeButtonEvent(ASK_NO_BTN);
    this.socialManager.removeButtonEvent(ASK_YES_BTN);

    this.socialManager.addButtonEvent(ASK_NO_BTN, (dbField, id) -> {
      final SocialPlayer player = this.queryPlayer(dbField, id);
      final AuthSession authSession = this.takeSession(player);
      if (authSession != null) {
        authSession.getEvent().completeAndCancel(this.askedKick);
        this.socialManager.broadcastMessage(player, Settings.IMP.MAIN.STRINGS.NOTIFY_WARN, this.keyboard);
      }
    });

    this.socialManager.addButtonEvent(ASK_YES_BTN, (dbField, id) -> {
      final SocialPlayer player = this.queryPlayer(dbField, id);
      final AuthSession authSession = this.takeSession(player);
      if (authSession != null) {
        if (this.auth2faWithoutPassword) {
          LimboPlayer limboPlayer = authSession.getPlayer();
          limboPlayer.disconnect();
          Player proxyPlayer = limboPlayer.getProxyPlayer();
          this.plugin.cacheAuthUser(proxyPlayer);
          this.plugin.updateLoginData(proxyPlayer);
        } else {
          authSession.getEvent().complete(TaskEvent.Result.NORMAL);
        }

        this.socialManager.broadcastMessage(player, Settings.IMP.MAIN.STRINGS.NOTIFY_THANKS, this.keyboard);
      }
    });
  }

  @Subscribe
  public void onAuth(PreAuthorizationEvent event) {
    Player proxyPlayer = event.getPlayer();
    SocialPlayer player = this.queryPlayer(proxyPlayer);
    if (player != null && player.isBlocked()) {
      event.cancel(this.blockedAccount);
      return;
    }

    if (this.auth2faWithoutPassword) {
      if (player != null && player.isTotpEnabled()) {
        event.setResult(TaskEvent.Result.WAIT);
        this.plugin.getAuthServer().spawnPlayer(proxyPlayer, new PreLoginLimboSessionHandler(this, event, player));
      }
    }
  }

  @Subscribe
  public void onAuthCompleted(PostAuthorizationEvent event) {
    Player proxyPlayer = event.getPlayer().getProxyPlayer();
    if (!this.auth2faWithoutPassword) {
      SocialPlayer player = this.queryPlayer(proxyPlayer);

      if (player != null && player.isTotpEnabled()) {
        event.setResult(TaskEvent.Result.WAIT);
        this.authMainHook(player, event.getPlayer(), event);
      }
    }
  }

  public void authMainHook(final SocialPlayer player, final LimboPlayer limboPlayer, final TaskEvent event) {
    final Player proxyPlayer = limboPlayer.getProxyPlayer();
    if (!this.socialManager.canDeliver(player) || !this.registerSession(player, limboPlayer, event)) {
      event.completeAndCancel(this.socialUnavailable);
      return;
    }
    final String ip = proxyPlayer.getRemoteAddress().getAddress().getHostAddress();
    this.socialManager.broadcastMessage(player, Placeholders.replace(Settings.IMP.MAIN.STRINGS.NOTIFY_ASK_VALIDATE,
            ip, Optional.ofNullable(this.geoIp).map(nonNullGeo -> nonNullGeo.getLocation(ip)).orElse("")),
        this.yesNoButtons, AbstractSocial.ButtonVisibility.PREFER_INLINE);
    proxyPlayer.sendMessage(this.askedValidate);
  }

  @Subscribe
  public void onServerConnected(ServerPostConnectEvent event) {
    Player proxyPlayer = event.getPlayer();
    if (this.stopped || event.getPreviousServer() != null || !Settings.IMP.MAIN.ENABLE_NOTIFY || !proxyPlayer.isActive()) {
      return;
    }
    final Object notification = new Object();
    if (this.joinNotifications.putIfAbsent(proxyPlayer, notification) != null) {
      return;
    }
    this.submitNotification(() -> this.notifyJoin(proxyPlayer, notification));
  }

  private void notifyJoin(final Player proxyPlayer, final Object notification) {
    if (!this.isCurrentNotification(proxyPlayer, notification)) {
      return;
    }
    // Optional SQL and GeoIP run outside proxy events and mandatory authentication.
    final SocialPlayer player = this.queryPlayer(proxyPlayer);
    if (player == null || !player.isNotifyEnabled() || !this.isCurrentNotification(proxyPlayer, notification)) {
      return;
    }
    final String ip = proxyPlayer.getRemoteAddress().getAddress().getHostAddress();
    final String message = Placeholders.replace(Settings.IMP.MAIN.STRINGS.NOTIFY_JOIN,
        ip, Optional.ofNullable(this.geoIp).map(nonNullGeo -> nonNullGeo.getLocation(ip)).orElse(""));
    if (this.isCurrentNotification(proxyPlayer, notification)) {
      this.socialManager.broadcastMessage(player, message, this.keyboard);
    }
  }

  private boolean isCurrentNotification(final Player player, final Object notification) {
    return !this.stopped && player.isActive() && this.joinNotifications.get(player) == notification;
  }

  @Subscribe
  public void onPlayerLeave(DisconnectEvent event) {
    final Player proxyPlayer = event.getPlayer();
    this.joinNotifications.remove(proxyPlayer);
    this.sessions.computeIfPresent(proxyPlayer.getUsername().toLowerCase(Locale.ROOT),
        (nickname, session) -> session.getPlayer().getProxyPlayer() == proxyPlayer ? null : session);
    if (this.stopped || !Settings.IMP.MAIN.ENABLE_NOTIFY || proxyPlayer.getCurrentServer().isEmpty()) {
      return;
    }
    this.submitNotification(() -> {
      final SocialPlayer player = this.queryPlayer(proxyPlayer);
      if (!this.stopped && player != null && player.isNotifyEnabled()) {
        this.socialManager.broadcastMessage(player, Settings.IMP.MAIN.STRINGS.NOTIFY_LEAVE, this.keyboard);
      }
    });
  }

  private boolean registerSession(final SocialPlayer player, final LimboPlayer limboPlayer, final TaskEvent event) {
    // Publishing and stopping share ownership; late availability replies cannot leave a waiting session behind.
    synchronized (this.sessions) {
      return !this.stopped && limboPlayer.getProxyPlayer().isActive()
          && this.sessions.putIfAbsent(player.getLowercaseNickname(), new AuthSession(event, limboPlayer)) == null;
    }
  }

  private AuthSession takeSession(final SocialPlayer player) {
    synchronized (this.sessions) {
      if (this.stopped || player == null) {
        return null;
      }
      final AuthSession session = this.sessions.remove(player.getLowercaseNickname());
      return session != null && session.getPlayer().getProxyPlayer().isActive() ? session : null;
    }
  }

  private void submitNotification(final Runnable notification) {
    final long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    try {
      this.notifications.execute(() -> {
        if (this.stopped || System.nanoTime() >= expires) {
          return;
        }
        try {
          notification.run();
        } catch (final RuntimeException failure) {
          LOGGER.warn("Optional social notification failed: {}", failure.getClass().getSimpleName());
        }
      });
    } catch (final RejectedExecutionException rejected) {
      if (!this.stopped) {
        LOGGER.warn("Optional social notification queue is full; notification skipped");
      }
    }
  }

  public void stop() {
    final List<AuthSession> pending;
    synchronized (this.sessions) {
      this.stopped = true;
      pending = List.copyOf(this.sessions.values());
      this.sessions.clear();
    }
    this.joinNotifications.clear();
    this.notifications.shutdownNow();
    // Task completion can dispatch proxy work, so it must run outside the session monitor.
    pending.forEach(session -> session.getEvent().completeAndCancel(this.socialUnavailable));
  }

  @Subscribe
  public void onUnregister(AuthUnregisterEvent event) {
    this.addon.unregisterPlayer(event.getNickname());
  }

  private SocialPlayer queryPlayer(Player player) {
    try {
      return this.socialPlayerDao.queryForId(player.getUsername().toLowerCase(Locale.ROOT));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private SocialPlayer queryPlayer(String dbField, Long id) {
    try {
      List<SocialPlayer> l = this.socialPlayerDao.queryForEq(dbField, id);

      if (l.size() == 0) {
        return null;
      }

      return l.get(0);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class AuthSession {
    private final TaskEvent event;
    private final LimboPlayer player;

    private AuthSession(TaskEvent event, LimboPlayer player) {
      this.event = event;
      this.player = player;
    }

    public TaskEvent getEvent() {
      return this.event;
    }

    public LimboPlayer getPlayer() {
      return this.player;
    }
  }
}
