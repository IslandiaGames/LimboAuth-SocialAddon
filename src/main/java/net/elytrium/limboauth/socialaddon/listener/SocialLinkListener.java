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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.elytrium.limboauth.socialaddon.SocialLinkMenu;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import org.slf4j.Logger;

/** Invites unlinked players only after authentication and the first real server connection. */
public final class SocialLinkListener {

  private final Dao<SocialPlayer, String> players;
  private final SocialLinkMenu menu;
  private final Logger logger;
  private final Set<Player> greeted = ConcurrentHashMap.newKeySet();

  public SocialLinkListener(Dao<SocialPlayer, String> players, SocialLinkMenu menu, Logger logger) {
    this.players = players;
    this.menu = menu;
    this.logger = logger;
  }

  @Subscribe
  public void onServerConnected(ServerPostConnectEvent event) {
    Player player = event.getPlayer();
    if (event.getPreviousServer() != null || !player.isActive() || !this.greeted.add(player)) {
      return;
    }
    try {
      SocialPlayer social = this.players.queryForId(player.getUsername().toLowerCase(Locale.ROOT));
      if (social != null && (social.getTelegramID() != null || social.getDiscordID() != null || social.getVkID() != null)) {
        return;
      }
      if (player.isActive() && this.greeted.contains(player)) {
        this.menu.announcement(player).ifPresent(player::sendMessage);
      }
    } catch (SQLException error) {
      this.logger.warn("Could not read social links for the account invitation", error);
    }
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    this.greeted.remove(event.getPlayer());
  }
}
