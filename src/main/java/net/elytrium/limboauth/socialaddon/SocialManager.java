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

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.AbstractSocial;
import net.elytrium.limboauth.socialaddon.social.DiscordSocial;
import net.elytrium.limboauth.socialaddon.social.SocialButtonListenerAdapter;
import net.elytrium.limboauth.socialaddon.social.SocialMessageListenerAdapter;
import net.elytrium.limboauth.socialaddon.social.TelegramSocial;
import net.elytrium.limboauth.socialaddon.social.VKSocial;
import org.slf4j.Logger;

public class SocialManager {

  private final List<SocialRuntime> providers;
  private final List<SocialMessageListenerAdapter> messageEvents = new CopyOnWriteArrayList<>();
  private final Map<String, SocialButtonListenerAdapter> buttonEvents = new ConcurrentHashMap<>();
  private final Map<String, String> buttonIdMap = new ConcurrentHashMap<>();
  private final SocialStatusWriter status;
  private volatile boolean stopped;

  public SocialManager(Logger logger, Path statusPath) {
    this.providers = List.of(
        new SocialRuntime("discord", new DiscordSocial(this::onMessageReceived, this::onButtonClicked), logger),
        new SocialRuntime("telegram", new TelegramSocial(this::onMessageReceived, this::onButtonClicked), logger),
        new SocialRuntime("vk", new VKSocial(this::onMessageReceived, this::onButtonClicked), logger));
    this.status = new SocialStatusWriter(statusPath, this.providers, logger, Clock.systemUTC());
  }

  private void onMessageReceived(String dbField, Long id, String message) {
    if (this.stopped) {
      return;
    }
    String buttonId = this.buttonIdMap.get(message);
    if (buttonId != null) {
      this.onButtonClicked(dbField, id, buttonId);
    }

    this.messageEvents.forEach(event -> {
      try {
        event.accept(dbField, id, message);
      } catch (Exception e) {
        this.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.SOCIAL_EXCEPTION_CAUGHT);
        if (Settings.IMP.MAIN.DEBUG) {
          e.printStackTrace(); // printStackTrace is necessary there
        }
      }
    });
  }

  private void onButtonClicked(String dbField, Long id, String buttonId) {
    if (this.stopped) {
      return;
    }
    SocialButtonListenerAdapter buttonListenerAdapter = this.buttonEvents.get(buttonId);
    if (buttonListenerAdapter != null) {
      try {
        buttonListenerAdapter.accept(dbField, id);
      } catch (Exception e) {
        this.broadcastMessage(dbField, id, Settings.IMP.MAIN.STRINGS.SOCIAL_EXCEPTION_CAUGHT);
        if (Settings.IMP.MAIN.DEBUG) {
          e.printStackTrace(); // printStackTrace is necessary there
        }
      }
    }
  }

  public void addMessageEvent(SocialMessageListenerAdapter event) {
    this.messageEvents.add(event);
  }

  public void addButtonEvent(String id, SocialButtonListenerAdapter event) {
    this.buttonEvents.put(id, event);
  }

  public void removeButtonEvent(String id) {
    this.buttonEvents.remove(id);
  }

  public void start() {
    this.providers.forEach(SocialRuntime::start);
    this.status.start();
  }

  public void stop() {
    this.stopped = true;
    this.providers.forEach(SocialRuntime::stop);
    this.status.stop();
  }

  public boolean canDeliver(SocialPlayer player) {
    return this.providers.stream().anyMatch(provider -> provider.isAvailable() && provider.getSocial().canSend(player));
  }

  public void unregisterHook(SocialPlayer player) {
    this.providers.stream()
        .filter(e -> e.getSocial().canSend(player))
        .forEach(e -> e.execute(social -> social.onPlayerRemoved(player)));
  }

  public void unregisterHook(String dbField, SocialPlayer player) {
    this.providers.stream()
        .filter(e -> e.getSocial().canSend(player))
        .filter(e -> e.getSocial().getDbField().equals(dbField))
        .forEach(e -> e.execute(social -> social.onPlayerRemoved(player)));
  }

  public void registerHook(String dbField, Long id) {
    this.providers.stream()
        .filter(e -> e.getSocial().getDbField().equals(dbField))
        .forEach(e -> e.execute(social -> social.onPlayerAdded(id)));
  }

  public void registerKeyboard(List<List<AbstractSocial.ButtonItem>> keyboard) {
    for (List<AbstractSocial.ButtonItem> items : keyboard) {
      for (AbstractSocial.ButtonItem item : items) {
        this.buttonIdMap.put(item.getValue(), item.getId());
      }
    }
  }

  public void registerButton(AbstractSocial.ButtonItem item) {
    this.buttonIdMap.put(item.getValue(), item.getId());
  }

  public void broadcastMessage(SocialPlayer player, String message, List<List<AbstractSocial.ButtonItem>> item) {
    this.broadcastMessage(player, message, item, AbstractSocial.ButtonVisibility.DEFAULT);
  }

  public void broadcastMessage(SocialPlayer player, String message,
                               List<List<AbstractSocial.ButtonItem>> item, AbstractSocial.ButtonVisibility visibility) {
    this.providers.stream()
        .filter(e -> e.getSocial().canSend(player))
        .forEach(e -> e.execute(social -> social.sendMessage(player, message, item, visibility)));
  }

  public void broadcastMessage(SocialPlayer player, String message) {
    this.providers.stream()
        .filter(e -> e.getSocial().canSend(player))
        .forEach(e -> e.execute(social -> social.sendMessage(player, message)));
  }

  public void broadcastMessage(String dbField, Long id, String message, List<List<AbstractSocial.ButtonItem>> item) {
    this.broadcastMessage(dbField, id, message, item, AbstractSocial.ButtonVisibility.DEFAULT);
  }


  public void broadcastMessage(String dbField, Long id, String message,
                               List<List<AbstractSocial.ButtonItem>> item, AbstractSocial.ButtonVisibility visibility) {
    this.providers.stream()
        .filter(e -> e.getSocial().getDbField().equals(dbField))
        .forEach(e -> e.execute(social -> social.sendMessage(id, message, item, visibility)));
  }

  public void broadcastMessage(String dbField, Long id, String message) {
    this.providers.stream()
        .filter(e -> e.getSocial().getDbField().equals(dbField))
        .forEach(e -> e.execute(social -> social.sendMessage(id, message)));
  }
}
