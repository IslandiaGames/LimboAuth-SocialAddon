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
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import net.elytrium.limboauth.socialaddon.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.WebhookInfo;

class TelegramIdentityTest {

  @TempDir
  Path directory;

  @Test
  void public_contact_uses_authenticated_bot_identity_when_configuration_is_blank() throws Exception {
    Settings settings = new Settings();
    settings.reload(this.directory.resolve("telegram.yml").toFile(), "Islandia");
    settings.MAIN.TELEGRAM.ENABLED = true;
    settings.MAIN.TELEGRAM.CONTACT = "";
    Settings.IMP.MAIN = settings.MAIN;
    User identity = new User();
    identity.setId(123L);
    identity.setUserName("islandia_fixture_bot");
    WebhookInfo webhook = new WebhookInfo();
    webhook.setUrl("");
    try (MockedConstruction<TelegramClient> clients = mockConstruction(TelegramClient.class, (client, context) -> {
      when(client.execute(any(GetMe.class))).thenReturn(identity);
      when(client.execute(any(GetWebhookInfo.class))).thenReturn(webhook);
      when(client.execute(any(GetUpdates.class))).thenReturn(new ArrayList<>());
    })) {
      TelegramSocial social = new TelegramSocial((field, id, text) -> { }, (field, id, button) -> { });
      try {
        social.start();
        await().atMost(Duration.ofSeconds(2)).until(social::isHealthy);
        assertThat(social.getPublicContact()).isEqualTo("https://t.me/islandia_fixture_bot");
        assertThat(clients.constructed()).hasSize(1);
        assertThat(settings.MAIN.TELEGRAM.CONTACT).isEmpty();
      } finally {
        social.stop();
      }
    }
  }
}
