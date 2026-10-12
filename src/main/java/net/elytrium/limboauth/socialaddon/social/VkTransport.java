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

import com.vk.api.sdk.client.ClientResponse;
import com.vk.api.sdk.client.TransportClient;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.http.Header;

/** Implements the SDK transport with owned connections and bounded requests. */
final class VkTransport implements TransportClient, AutoCloseable {

  private static final String FORM = "application/x-www-form-urlencoded";
  private final SocialHttpClient http = new SocialHttpClient();

  private ClientResponse call(String method, String url, String body, String type, Header[] headers) throws IOException {
    Request.Builder request = new Request.Builder().url(url);
    for (Header header : headers) {
      request.header(header.getName(), header.getValue());
    }
    request.method(method, body == null ? null : RequestBody.create(MediaType.get(type), body));
    return new ClientResponse(200, this.http.execute(request.build()), Map.of());
  }

  @Override
  public ClientResponse post(String url, String body) throws IOException {
    return this.post(url, body, FORM);
  }

  @Override
  public ClientResponse post(String url, String fileName, File file) throws IOException {
    throw new IOException("Social authentication does not upload files");
  }

  @Override
  public ClientResponse post(String url, String body, String contentType) throws IOException {
    return this.call("POST", url, body, contentType, new Header[0]);
  }

  @Override
  public ClientResponse post(String url) throws IOException {
    return this.post(url, "");
  }

  @Override
  public ClientResponse post(String url, String body, Header[] headers) throws IOException {
    return this.call("POST", url, body, FORM, headers);
  }

  @Override
  public ClientResponse get(String url) throws IOException {
    return this.get(url, FORM);
  }

  @Override
  public ClientResponse get(String url, String contentType) throws IOException {
    return this.call("GET", url, null, contentType, new Header[0]);
  }

  @Override
  public ClientResponse get(String url, Header[] headers) throws IOException {
    return this.call("GET", url, null, FORM, headers);
  }

  @Override
  public ClientResponse delete(String url) throws IOException {
    return this.delete(url, "");
  }

  @Override
  public ClientResponse delete(String url, String body) throws IOException {
    return this.delete(url, body, FORM);
  }

  @Override
  public ClientResponse delete(String url, String body, String contentType) throws IOException {
    return this.call("DELETE", url, body, contentType, new Header[0]);
  }

  @Override
  public ClientResponse delete(String url, String body, Header[] headers) throws IOException {
    return this.call("DELETE", url, body, FORM, headers);
  }

  @Override
  public void close() {
    this.http.close();
  }
}
