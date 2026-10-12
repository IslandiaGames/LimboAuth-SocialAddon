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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

class SocialHttpClientTest {

  @Test
  void redirects_and_oversized_responses_are_rejected_without_following_other_destinations() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger destinationCalls = new AtomicInteger();
    server.createContext("/destination", exchange -> {
      destinationCalls.incrementAndGet();
      exchange.sendResponseHeaders(200, 0);
      exchange.close();
    });
    server.createContext("/redirect", exchange -> {
      exchange.getResponseHeaders().add("Location", "/destination");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/large", exchange -> {
      byte[] bytes = new byte[2 * 1024 * 1024 + 1];
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    try (SocialHttpClient client = new SocialHttpClient()) {
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      assertThatThrownBy(() -> client.execute(new Request.Builder().url(origin + "/redirect").build()))
          .isInstanceOf(IOException.class).hasMessageContaining("302");
      assertThat(destinationCalls.get()).isZero();
      assertThatThrownBy(() -> client.execute(new Request.Builder().url(origin + "/large").build()))
          .isInstanceOf(IOException.class).hasMessageContaining("size limit");
    } finally {
      server.stop(0);
    }
  }
}
