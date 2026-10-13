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

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.elytrium.limboauth.socialaddon.Settings;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class TelegramSocial extends AbstractSocial {

  private final boolean enabled = Settings.IMP.MAIN.TELEGRAM.ENABLED;
  private final String token = Settings.IMP.MAIN.TELEGRAM.TOKEN;
  private final String origin = Settings.IMP.MAIN.TELEGRAM.API_URL;
  private volatile TelegramClient client;
  private volatile ScheduledExecutorService polling;
  private volatile long lastPoll;
  private int offset;
  private int failures;
  private long retryAt;

  public TelegramSocial(SocialMessageListener onMessageReceived, SocialButtonListener onButtonClicked) {
    super(onMessageReceived, onButtonClicked);
  }

  @Override
  public boolean isEnabled() {
    return this.enabled;
  }

  @Override
  public void start() throws SocialInitializationException {
    try {
      this.client = new TelegramClient(this.origin, this.token);
      String username = this.client.execute(new GetMe()).getUserName();
      if (username != null && username.matches("[A-Za-z0-9_]{5,32}")) {
        this.setPublicContact("https://t.me/" + username);
      }
      String webhook = this.client.execute(new GetWebhookInfo()).getUrl();
      if (webhook != null && !webhook.isEmpty()) {
        throw new TelegramApiException("Telegram webhook must be removed before enabling polling");
      }
      this.polling = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "social-telegram-poll");
        thread.setDaemon(true);
        return thread;
      });
      this.polling.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    } catch (TelegramApiException error) {
      throw new SocialInitializationException(error);
    }
  }

  private void poll() {
    if (System.nanoTime() < this.retryAt) {
      return;
    }
    try {
      GetUpdates request = new GetUpdates();
      request.setOffset(this.offset);
      request.setTimeout(25);
      request.setLimit(100);
      request.setAllowedUpdates(List.of("message", "callback_query"));
      final List<Update> updates = this.client.execute(request);
      this.lastPoll = System.nanoTime();
      this.clearFailure();
      this.failures = 0;
      for (Update update : updates) {
        this.offset = update.getUpdateId() + 1;
        this.onUpdate(update);
      }
    } catch (TelegramApiException | RuntimeException error) {
      this.recordFailure(error);
      this.lastPoll = 0;
      this.failures = Math.min(6, this.failures + 1);
      this.retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(60, 1L << this.failures));
    }
  }

  @Override
  public boolean isHealthy() {
    long last = this.lastPoll;
    return last != 0 && System.nanoTime() - last < TimeUnit.SECONDS.toNanos(90);
  }

  @Override
  public void stop() {
    this.lastPoll = 0;
    if (this.polling != null) {
      this.polling.shutdownNow();
    }
    if (this.client != null) {
      this.client.close();
    }
  }

  @Override
  public String getDbField() {
    return SocialPlayer.TELEGRAM_DB_FIELD;
  }

  @Override
  public void onPlayerAdded(Long id) {

  }

  @Override
  public void onPlayerRemoved(SocialPlayer player) {

  }

  @Override
  public void sendMessage(Long id, String content, List<List<ButtonItem>> buttons, ButtonVisibility visibility) {
    ReplyKeyboard keyboard;
    switch (visibility) {
      case PREFER_INLINE: {
        keyboard = new InlineKeyboardMarkup(buttons.stream().map(row -> row.stream().map(e -> {
          InlineKeyboardButton button = new InlineKeyboardButton();
          button.setText(e.getValue());
          button.setCallbackData(e.getId());

          return button;
        }).collect(Collectors.toList())).collect(Collectors.toList()));
        break;
      }
      default:
      case PREFER_KEYBOARD: {
        keyboard = new ReplyKeyboardMarkup(buttons.stream().map(row ->
            new KeyboardRow(row.stream().map(e ->
                new KeyboardButton(e.getValue())).collect(Collectors.toList())))
            .collect(Collectors.toList()));
        break;
      }
    }

    SendMessage message = new SendMessage();
    message.setChatId(String.valueOf(id));
    message.setText(content);
    if (!buttons.isEmpty()) {
      message.setReplyMarkup(keyboard);
    }
    try {
      this.client.execute(message);
    } catch (TelegramApiException error) {
      throw new IllegalStateException("Telegram message delivery failed");
    }
  }

  @Override
  public void sendMessage(SocialPlayer player, String content, List<List<ButtonItem>> buttons, ButtonVisibility visibility) {
    this.sendMessage(player.getTelegramID(), content, buttons, visibility);
  }

  @Override
  public boolean canSend(SocialPlayer player) {
    return player.getTelegramID() != null;
  }

  private void onUpdate(Update update) throws TelegramApiException {
    if (update.hasMessage()) {
      Message message = update.getMessage();
      if (message != null && message.hasText() && message.getChat().isUserChat()) {
        this.proceedMessage(SocialPlayer.TELEGRAM_DB_FIELD, message.getFrom().getId(), message.getText());
      }
    }
    if (update.hasCallbackQuery()) {
      CallbackQuery query = update.getCallbackQuery();
      if (query != null) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(query.getId());
        this.client.execute(answer);
        this.proceedButton(SocialPlayer.TELEGRAM_DB_FIELD, query.getFrom().getId(), query.getData());
      }
    }
  }
}
