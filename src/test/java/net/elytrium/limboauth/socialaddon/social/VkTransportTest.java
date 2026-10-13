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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpServer;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VkTransportTest {

  @Test
  void actual_sdk_reads_identity_and_api_errors_through_http_transport() throws Exception {
    AtomicReference<String> body = new AtomicReference<>("{\"response\":[{\"id\":123,\"name\":\"Fixture\",\"screen_name\":\"fixture\"}]}");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/method/groups.getById", exchange -> {
      byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    try (VkTransport transport = new VkTransport()) {
      VkApiClient client = spy(new VkApiClient(transport, new GsonBuilder().create(), 1));
      when(client.getApiEndpoint()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/method/");
      GroupActor actor = new GroupActor(0, "fixture-token");
      assertThat(client.groups().getByIdObjectLegacy(actor).groupIds(Collections.emptyList()).execute())
          .singleElement().satisfies(group -> assertThat(group.getId()).isEqualTo(123));
      body.set("{\"error\":{\"error_code\":15,\"error_msg\":\"Access denied\",\"request_params\":[]}}");
      assertThatThrownBy(() -> client.groups().getByIdObjectLegacy(actor).groupIds(Collections.emptyList()).execute())
          .isInstanceOf(ApiException.class).satisfies(error -> assertThat(SocialFailureReason.describe(error)).isEqualTo("vk_api_15"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void actual_sdk_rejects_invalid_http_metadata_instead_of_accepting_a_success_payload() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/method/groups.getById", exchange -> {
      byte[] bytes = "{\"response\":[{\"id\":123}]}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(503, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    try (VkTransport transport = new VkTransport()) {
      VkApiClient client = spy(new VkApiClient(transport, new GsonBuilder().create(), 1));
      when(client.getApiEndpoint()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/method/");
      assertThatThrownBy(() -> client.groups().getByIdObjectLegacy(new GroupActor(0, "fixture-token"))
          .groupIds(Collections.emptyList()).execute()).isInstanceOf(ClientException.class).hasMessageContaining("503");
    } finally {
      server.stop(0);
    }
  }
}
