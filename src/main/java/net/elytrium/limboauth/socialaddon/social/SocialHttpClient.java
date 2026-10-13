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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Owns bounded HTTP calls without logging credential-bearing URLs or responses. */
public final class SocialHttpClient implements AutoCloseable {

  private static final int RESPONSE_LIMIT = 2 * 1024 * 1024;
  private final OkHttpClient client;

  public SocialHttpClient() {
    this.client = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();
  }

  public String execute(Request request) throws IOException {
    HttpResponse response = this.executeResponse(request);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Social API request failed: HTTP " + response.statusCode());
    }
    return response.body();
  }

  HttpResponse executeResponse(Request request) throws IOException {
    try (Response response = this.client.newCall(request).execute()) {
      if (response.body() == null) {
        throw new IOException("Social API request failed: HTTP " + response.code());
      }
      byte[] bytes = response.body().byteStream().readNBytes(RESPONSE_LIMIT + 1);
      if (bytes.length > RESPONSE_LIMIT) {
        throw new IOException("Social API response exceeds size limit");
      }
      return new HttpResponse(response.code(), response.header("Content-Type", ""), new String(bytes, StandardCharsets.UTF_8));
    }
  }

  record HttpResponse(int statusCode, String contentType, String body) {
  }

  @Override
  public void close() {
    this.client.dispatcher().cancelAll();
    this.client.dispatcher().executorService().shutdownNow();
    this.client.connectionPool().evictAll();
  }
}
