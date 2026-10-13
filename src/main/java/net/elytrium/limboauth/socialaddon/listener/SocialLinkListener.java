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
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.elytrium.limboauth.socialaddon.SocialLinkMenu;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import org.slf4j.Logger;

/** Shows account links only after authentication and the first real server connection. */
public final class SocialLinkListener {

  private static final long CONTACT_WAIT_NANOS = TimeUnit.MINUTES.toNanos(2);
  private final Dao<SocialPlayer, String> players;
  private final SocialLinkMenu menu;
  private final Logger logger;
  private final Map<Player, Invitation> greeted = new ConcurrentHashMap<>();
  private final Map<Player, Invitation> pending = new ConcurrentHashMap<>();
  private final LongSupplier clock;
  private volatile boolean stopped;

  public SocialLinkListener(Dao<SocialPlayer, String> players, SocialLinkMenu menu, Logger logger, LongSupplier clock) {
    this.players = players;
    this.menu = menu;
    this.logger = logger;
    this.clock = clock;
  }

  @Subscribe
  public void onServerConnected(ServerPostConnectEvent event) {
    Player player = event.getPlayer();
    if (this.stopped || event.getPreviousServer() != null || !player.isActive()) {
      return;
    }
    Invitation invitation = new Invitation(this.clock.getAsLong() + CONTACT_WAIT_NANOS);
    if (this.greeted.putIfAbsent(player, invitation) != null) {
      return;
    }
    // The scheduled retry owns database reads so a slow invitation lookup cannot delay connection events.
    this.pending.put(player, invitation);
  }

  public void retryPending() {
    this.pending.forEach(this::invite);
  }

  private void invite(Player player, Invitation invitation) {
    if (!this.isCurrent(player, invitation)) {
      this.pending.remove(player, invitation);
      return;
    }
    if (!this.menu.isAnnouncementAvailable() || !this.pending.remove(player, invitation)) {
      return;
    }
    try {
      SocialPlayer social = this.players.queryForId(player.getUsername().toLowerCase(Locale.ROOT));
      Set<SocialPlayer.DatabaseField> linkedServices = EnumSet.noneOf(SocialPlayer.DatabaseField.class);
      if (social != null) {
        for (SocialPlayer.DatabaseField field : SocialPlayer.DatabaseField.values()) {
          if (field.getIdFor(social) != null) {
            linkedServices.add(field);
          }
        }
      }
      if (this.isCurrent(player, invitation)) {
        this.menu.announcement(player, linkedServices).ifPresent(player::sendMessage);
      }
    } catch (SQLException error) {
      this.logger.warn("Could not read social links for the account invitation", error);
    }
  }

  private boolean isCurrent(Player player, Invitation invitation) {
    // Identity prevents an old database response from greeting a later login with the same deadline.
    return !this.stopped && player.isActive() && this.greeted.get(player) == invitation
        && this.clock.getAsLong() < invitation.deadline;
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    this.greeted.remove(event.getPlayer());
    this.pending.remove(event.getPlayer());
  }

  public void stop() {
    this.stopped = true;
    this.pending.clear();
    this.greeted.clear();
  }

  private static final class Invitation {

    private final long deadline;

    private Invitation(long deadline) {
      this.deadline = deadline;
    }
  }
}
