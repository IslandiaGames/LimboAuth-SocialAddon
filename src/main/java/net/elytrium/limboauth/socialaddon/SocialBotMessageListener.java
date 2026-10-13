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

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.AbstractSocial;
import net.elytrium.limboauth.socialaddon.social.SocialMessageListenerAdapter;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;

/** Routes private commands and provides help without changing account access. */
final class SocialBotMessageListener implements SocialMessageListenerAdapter {

  private final Settings.MAIN settings;
  // Addon assembles listeners before publishing its initialized DAO on startup and reload.
  private final Supplier<Dao<SocialPlayer, String>> dao;
  private final SocialManager socialManager;
  private final List<List<AbstractSocial.ButtonItem>> keyboard;
  private final SocialMessageListenerAdapter commands;

  SocialBotMessageListener(Settings.MAIN settings, Supplier<Dao<SocialPlayer, String>> dao, SocialManager socialManager,
                           List<List<AbstractSocial.ButtonItem>> keyboard, SocialMessageListenerAdapter commands) {
    this.settings = settings;
    this.dao = dao;
    this.socialManager = socialManager;
    this.keyboard = keyboard;
    this.commands = commands;
  }

  @Override
  public void accept(String dbField, Long id, String content) throws Exception {
    String message = content.strip();
    if (this.settings.SOCIAL_REGISTER_CMDS.stream().anyMatch(command -> matchesCommand(message, command))
        || this.settings.SOCIAL_LINK_CMDS.stream().anyMatch(command -> matchesCommand(message, command))) {
      this.commands.accept(dbField, id, message);
      return;
    }
    boolean requestedHelp = this.settings.START_MESSAGES.stream().anyMatch(command -> message.equalsIgnoreCase(command.strip()))
        || this.settings.FORCE_KEYBOARD_CMDS.stream().anyMatch(command -> message.equalsIgnoreCase(command.strip()));
    String help = requestedHelp ? "" : this.settings.STRINGS.BOT_HELP + "\n\n";
    if (this.dao.get().queryForEq(dbField, id).isEmpty()) {
      this.socialManager.broadcastMessage(dbField, id, help + this.settings.START_REPLY);
      return;
    }
    this.socialManager.broadcastMessage(dbField, id, help + this.settings.STRINGS.KEYBOARD_RESTORED, this.keyboard);
  }

  static boolean matchesCommand(String message, String command) {
    String normalizedCommand = command.strip().toLowerCase(Locale.ROOT);
    String normalizedMessage = message.toLowerCase(Locale.ROOT);
    return !normalizedCommand.isEmpty() && normalizedMessage.startsWith(normalizedCommand)
        && (message.length() == normalizedCommand.length() || Character.isWhitespace(message.charAt(normalizedCommand.length())));
  }
}
