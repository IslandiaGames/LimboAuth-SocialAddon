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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

class TelegramClientTest {

  @Test
  void identity_polling_messages_and_callbacks_use_the_same_relay() throws Exception {
    SocialHttpClient transport = mock(SocialHttpClient.class);
    List<String> requests = new ArrayList<>();
    when(transport.execute(any(Request.class))).thenAnswer(invocation -> {
      Request request = invocation.getArgument(0);
      requests.add(request.url().toString());
      switch (request.url().pathSegments().get(1)) {
        case "getme":
          return "{\"ok\":true,\"result\":{\"id\":123,\"is_bot\":true,\"first_name\":\"Fixture\"}}";
        case "getupdates":
          return "{\"ok\":true,\"result\":[{\"update_id\":456,\"message\":{\"message_id\":1,"
              + "\"from\":{\"id\":321,\"is_bot\":false,\"first_name\":\"User\"},"
              + "\"chat\":{\"id\":321,\"type\":\"private\"},\"text\":\"menu\"}}]}";
        case "sendmessage":
          return "{\"ok\":true,\"result\":{\"message_id\":2,\"chat\":{\"id\":321,\"type\":\"private\"}}}";
        default:
          return "{\"ok\":true,\"result\":true}";
      }
    });
    try (TelegramClient client = new TelegramClient("https://relay.example.invalid/", "123:fixture", transport)) {
      assertThat(client.execute(new GetMe()).getId()).isEqualTo(123);
      assertThat(client.execute(new GetUpdates()).get(0).getMessage().getText()).isEqualTo("menu");
      SendMessage message = new SendMessage("321", "fixture");
      assertThat(client.execute(message).getMessageId()).isEqualTo(2);
      AnswerCallbackQuery answer = new AnswerCallbackQuery();
      answer.setCallbackQueryId("123");
      assertThat(client.execute(answer)).isTrue();
      assertThat(requests).containsExactly(
          "https://relay.example.invalid/bot123:fixture/getme",
          "https://relay.example.invalid/bot123:fixture/getupdates",
          "https://relay.example.invalid/bot123:fixture/sendmessage",
          "https://relay.example.invalid/bot123:fixture/answercallbackquery");
    }
    verify(transport).close();
  }

  @Test
  void unsafe_origins_are_rejected_and_network_errors_do_not_retain_tokens() throws Exception {
    SocialHttpClient transport = mock(SocialHttpClient.class);
    for (String origin : List.of("http://relay.invalid", "https://user:pass@relay.invalid", "https://relay.invalid/bot",
        "https://relay.invalid?token=secret", "https://relay.invalid#fragment")) {
      assertThatThrownBy(() -> new TelegramClient(origin, "123:fixture", transport)).isInstanceOf(IllegalArgumentException.class);
    }
    when(transport.execute(any(Request.class))).thenThrow(new IOException("secret token in upstream exception"));
    try (TelegramClient client = new TelegramClient("https://relay.invalid", "123:fixture", transport)) {
      assertThatThrownBy(() -> client.execute(new GetMe())).isInstanceOf(TelegramApiException.class)
          .hasMessage("Telegram transport unavailable").hasNoCause();
    }
  }
}
