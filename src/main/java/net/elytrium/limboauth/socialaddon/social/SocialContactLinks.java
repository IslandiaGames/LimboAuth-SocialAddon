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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/** Converts community pages to message entry links without resolving public identities over the network. */
public final class SocialContactLinks {

  private static final Set<String> VK_HOSTS = Set.of("vk.com", "www.vk.com", "m.vk.com", "vk.ru", "www.vk.ru", "m.vk.ru");
  private static final Set<String> VK_ROUTES = Set.of("im", "feed", "groups", "friends", "settings", "login", "join", "support");

  private SocialContactLinks() {
  }

  public static String vkMessages(String url) {
    try {
      URI uri = new URI(url);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getPort() != -1 || !VK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
        return url;
      }
      String path = uri.getRawPath();
      if (path == null || !path.matches("/[A-Za-z0-9_.]+/?")) {
        return url;
      }
      String community = path.substring(1).replaceAll("/$", "");
      if (VK_ROUTES.contains(community.toLowerCase(Locale.ROOT))) {
        return url;
      }
      if (community.matches("(?:public|event)[0-9]+")) {
        community = "club" + community.replaceFirst("^(?:public|event)", "");
      }
      String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
      String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();
      return "https://vk.me/" + community + query + fragment;
    } catch (URISyntaxException error) {
      return url;
    }
  }
}
