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

package net.elytrium.limboauth.socialaddon.social;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.vk.api.sdk.actions.LongPoll;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.exceptions.LongPollServerKeyExpiredException;
import com.vk.api.sdk.objects.callback.longpoll.responses.GetLongPollEventsResponse;
import com.vk.api.sdk.objects.groups.responses.GetLongPollServerResponse;
import com.vk.api.sdk.objects.messages.Keyboard;
import com.vk.api.sdk.objects.messages.KeyboardButton;
import com.vk.api.sdk.objects.messages.KeyboardButtonAction;
import com.vk.api.sdk.objects.messages.KeyboardButtonColor;
import com.vk.api.sdk.objects.messages.TemplateActionTypeNames;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.elytrium.limboauth.socialaddon.Settings;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;

public class VKSocial extends AbstractSocial {
  private final boolean enabled = Settings.IMP.MAIN.VK.ENABLED;
  private final String token = Settings.IMP.MAIN.VK.TOKEN;
  private volatile VkTransport transport;
  private VkApiClient vk;
  private GroupActor actor;
  private volatile boolean polling;
  private volatile Thread thread;
  private volatile long lastPoll;

  public VKSocial(SocialMessageListener onMessageReceived, SocialButtonListener onButtonClicked) {
    super(onMessageReceived, onButtonClicked);
  }

  @Override
  public boolean isEnabled() {
    return this.enabled;
  }

  @Override
  public void stop() {
    this.polling = false;
    this.lastPoll = 0;
    if (this.thread != null) {
      this.thread.interrupt();
    }
    if (this.transport != null) {
      this.transport.close();
    }
  }

  @Override
  public boolean isRunning() {
    return this.polling && this.thread != null && this.thread.isAlive();
  }

  @Override
  public boolean isHealthy() {
    long last = this.lastPoll;
    return this.polling && last != 0 && System.nanoTime() - last < TimeUnit.SECONDS.toNanos(90);
  }

  @Override
  public void start() throws SocialInitializationException {
    this.transport = new VkTransport();
    this.vk = new VkApiClient(this.transport, new GsonBuilder().disableHtmlEscaping().create(), 1);
    try {
      GroupActor tempActor = new GroupActor(0, this.token);
      int groupId = this.vk.groups().getByIdObjectLegacy(tempActor).groupIds(Collections.emptyList()).execute().get(0).getId();
      this.actor = new GroupActor(groupId, this.token);
      this.setPublicContact("https://vk.me/club" + groupId);
      this.vk.groups().setLongPollSettings(this.actor, groupId).enabled(true)
          .messageEvent(true).messageNew(true).execute();
    } catch (ApiException | ClientException error) {
      throw new SocialInitializationException(error);
    }
    this.polling = true;
    this.thread = new Thread(this::poll, "social-vk-poll");
    this.thread.setDaemon(true);
    this.thread.start();
  }

  private void poll() {
    int delay = 5;
    while (this.polling && !Thread.currentThread().isInterrupted()) {
      try {
        GetLongPollServerResponse serverInfo = this.vk.groups().getLongPollServer(this.actor, this.actor.getGroupId()).execute();
        LongPoll longPoll = new LongPoll(this.vk);
        String ts = serverInfo.getTs();
        while (this.polling && !Thread.currentThread().isInterrupted()) {
          GetLongPollEventsResponse response = longPoll.getEvents(serverInfo.getServer(), serverInfo.getKey(), ts).waitTime(25).execute();
          ts = response.getTs();
          this.lastPoll = System.nanoTime();
          this.clearFailure();
          delay = 5;
          response.getUpdates().forEach(this::onUpdate);
        }
      } catch (LongPollServerKeyExpiredException expired) {
        this.recordFailure(expired);
        this.lastPoll = 0;
      } catch (ClientException | ApiException | RuntimeException error) {
        this.recordFailure(error);
        this.lastPoll = 0;
        try {
          TimeUnit.SECONDS.sleep(delay);
          delay = Math.min(60, delay * 2);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private void onUpdate(JsonObject update) {
    if (update.has("type") && update.has("object") && update.get("object").isJsonObject()) {
      JsonObject object = update.getAsJsonObject("object");
      switch (update.get("type").getAsString()) {
        case "message_new":
          this.onMessageNew(object);
          break;
        case "message_event":
          this.onMessageEvent(object);
          break;
        default:
          break;
      }
    }
  }

  @Override
  public String getDbField() {
    return SocialPlayer.VK_DB_FIELD;
  }

  @Override
  public void onPlayerAdded(Long id) {

  }

  @Override
  public void onPlayerRemoved(SocialPlayer player) {

  }

  @Override
  public void sendMessage(Long id, String content, List<List<ButtonItem>> buttons, ButtonVisibility visibility) {
    List<List<KeyboardButton>> vkButtons = buttons.stream().map(row -> row.stream().map(button -> {
      KeyboardButtonColor color;
      switch (button.getColor()) {
        case RED:
          color = KeyboardButtonColor.NEGATIVE;
          break;
        case GREEN:
          color = KeyboardButtonColor.POSITIVE;
          break;
        case SECONDARY:
        case LINK:
          color = KeyboardButtonColor.DEFAULT;
          break;
        case PRIMARY:
        default:
          color = KeyboardButtonColor.PRIMARY;
          break;
      }

      JsonObject payload = new JsonObject();
      payload.addProperty("button", button.getId());

      return new KeyboardButton()
          .setColor(color)
          .setAction(
              new KeyboardButtonAction()
                  .setType(TemplateActionTypeNames.CALLBACK)
                  .setLabel(button.getValue())
                  .setPayload(payload.toString()));
    }).collect(Collectors.toList())).collect(Collectors.toList());

    try {
      if (buttons.isEmpty()) {
        this.vk.messages()
                .send(this.actor)
                .userId(id.intValue())
                .message(content)
                .randomId(ThreadLocalRandom.current().nextInt())
                .execute();
      } else {
        Keyboard keyboard = new Keyboard()
                .setButtons(vkButtons)
                .setInline(visibility == ButtonVisibility.PREFER_INLINE)
                .setOneTime(false);

        this.vk.messages()
                .send(this.actor)
                .userId(id.intValue())
                .message(content)
                .keyboard(keyboard)
                .randomId(ThreadLocalRandom.current().nextInt())
                .execute();
      }
    } catch (ClientException | ApiException e) {
      throw new IllegalStateException("VK message delivery failed");
    }
  }

  @Override
  public void sendMessage(SocialPlayer player, String content, List<List<ButtonItem>> buttons, ButtonVisibility visibility) {
    this.sendMessage(player.getVkID(), content, buttons, visibility);
  }

  @Override
  public boolean canSend(SocialPlayer player) {
    return player.getVkID() != null;
  }

  public void onMessageNew(JsonObject messageNew) {
    if (messageNew.has("message")) {
      JsonObject message = messageNew.get("message").getAsJsonObject();

      if (message.has("text")) {
        this.proceedMessage(SocialPlayer.VK_DB_FIELD, message.get("from_id").getAsLong(), message.get("text").getAsString());
      }
    }
  }

  public void onMessageEvent(JsonObject messageEvent) {
    if (messageEvent.has("payload")) {
      JsonObject payload = messageEvent.get("payload").getAsJsonObject();
      String eventId = messageEvent.get("event_id").getAsString();
      int userId = messageEvent.get("user_id").getAsInt();
      int peerId = messageEvent.get("peer_id").getAsInt();

      try {
        this.vk.messages()
            .sendMessageEventAnswer(this.actor, eventId, userId, peerId)
            .execute();
      } catch (ClientException | ApiException e) {
        throw new IllegalStateException("VK callback acknowledgement failed");
      }

      if (payload.has("button")) {
        this.proceedButton(SocialPlayer.VK_DB_FIELD, (long) userId, payload.get("button").getAsString());
      }
    }
  }
}
