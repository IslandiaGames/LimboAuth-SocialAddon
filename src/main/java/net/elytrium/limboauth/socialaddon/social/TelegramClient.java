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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/** Uses the same origin for identity, polling, messages and callbacks. */
final class TelegramClient implements AutoCloseable {

  private final SocialHttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();
  private final String baseUrl;

  TelegramClient(String origin, String token) {
    this(origin, token, new SocialHttpClient());
  }

  TelegramClient(String origin, String token, SocialHttpClient http) {
    this.http = http;
    URI uri = URI.create(origin);
    if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
        || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
        || !token.matches("[0-9]+:[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Invalid Telegram origin or token");
    }
    this.baseUrl = origin.replaceAll("/+$", "") + "/bot" + token + "/";
  }

  <T extends Serializable> T execute(BotApiMethod<T> method) throws TelegramApiException {
    method.validate();
    try {
      Request request = new Request.Builder().url(this.baseUrl + method.getMethod())
          .header("User-Agent", "Islandia-Monitoring/1.0")
          .post(RequestBody.create(MediaType.get("application/json"), this.mapper.writeValueAsBytes(method))).build();
      return method.deserializeResponse(this.http.execute(request));
    } catch (IOException error) {
      // Do not retain exceptions whose URLs may include the token.
      throw new TelegramApiException("Telegram transport unavailable");
    }
  }

  @Override
  public void close() {
    this.http.close();
  }
}
