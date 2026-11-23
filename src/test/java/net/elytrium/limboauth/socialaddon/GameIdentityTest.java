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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import net.elytrium.limboauth.LimboAuth;
import net.elytrium.limboauth.model.RegisteredPlayer;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import org.junit.jupiter.api.Test;

class GameIdentityTest {

  private static final String KEY = "test-only-identity-service-key-00000000";

  @Test
  @SuppressWarnings("unchecked")
  void verifies_the_persisted_game_password_and_honours_account_block_and_native_totp() throws Exception {
    var auth = mock(LimboAuth.class);
    var accounts = (Dao<RegisteredPlayer, String>) mock(Dao.class);
    when(auth.getPlayerDao()).thenReturn(accounts);
    net.elytrium.limboauth.Settings.IMP.MAIN = new net.elytrium.limboauth.Settings.MAIN();
    net.elytrium.limboauth.Settings.IMP.MAIN.BCRYPT_COST = 4;
    Settings.IMP.MAIN = new Settings.MAIN();
    var id = UUID.randomUUID().toString();
    var account = new RegisteredPlayer("PlayerOne", id, "192.0.2.1").setPassword("correct-game-password");
    when(accounts.queryForId("playerone")).thenReturn(account);
    var social = new SocialPlayer("playerone");
    social.setBlocked(false);
    social.setTelegramID(123L);
    var socials = (Dao<SocialPlayer, String>) mock(Dao.class);
    when(socials.queryForId("playerone")).thenReturn(social);
    try (var bridge = new GameIdentity(mock(Addon.class), auth, socials, KEY, URI.create("http://127.0.0.1:1"),
        "https://islandia.test/account/", new InetSocketAddress("127.0.0.1", 0))) {
      assertThat(verify(bridge, "wrong-service-key", "correct-game-password").statusCode()).isEqualTo(403);
      assertThat(verify(bridge, KEY, "wrong-password").body()).isEqualTo("null");
      var response = verify(bridge, KEY, "correct-game-password");
      assertThat(response.statusCode()).isEqualTo(200);
      var player = JsonParser.parseString(response.body()).getAsJsonObject();
      assertThat(player.get("id").getAsString()).isEqualTo(id);
      assertThat(player.get("hasSocial").getAsBoolean()).isTrue();
      assertThat(player.has("password")).isFalse();
      social.setBlocked(true);
      assertThat(verify(bridge, KEY, "correct-game-password").body()).isEqualTo("null");
      social.setBlocked(false);
      account.setTotpToken("JBSWY3DPEHPK3PXP");
      assertThat(verify(bridge, KEY, "correct-game-password").body()).isEqualTo("null");
    }
  }

  private static HttpResponse<String> verify(GameIdentity bridge, String key, String pass) throws Exception {
    String body = new Gson().toJson(Map.of("nickname", "PlayerOne", "password", pass, "code", ""));
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.port() + "/v1/verify"))
        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
