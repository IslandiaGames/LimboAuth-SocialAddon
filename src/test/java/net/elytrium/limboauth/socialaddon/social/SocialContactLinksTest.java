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

import org.junit.jupiter.api.Test;

class SocialContactLinksTest {

  @Test
  void community_pages_open_messages_while_explicit_message_links_and_custom_destinations_are_preserved() {
    assertThat(SocialContactLinks.vkMessages("https://vk.ru/islandia_games")).isEqualTo("https://vk.me/islandia_games");
    assertThat(SocialContactLinks.vkMessages("https://VK.COM/club123/")).isEqualTo("https://vk.me/club123");
    assertThat(SocialContactLinks.vkMessages("https://m.vk.ru/public123?ref=account")).isEqualTo("https://vk.me/club123?ref=account");
    assertThat(SocialContactLinks.vkMessages("https://vk.me/islandia_games?ref=account")).isEqualTo("https://vk.me/islandia_games?ref=account");
    assertThat(SocialContactLinks.vkMessages("https://vk.ru/write-123")).isEqualTo("https://vk.ru/write-123");
    assertThat(SocialContactLinks.vkMessages("https://vk.com/im?sel=-123")).isEqualTo("https://vk.com/im?sel=-123");
    assertThat(SocialContactLinks.vkMessages("https://accounts.example.test/vk")).isEqualTo("https://accounts.example.test/vk");
    assertThat(SocialContactLinks.vkMessages("https://vk.ru.example.test/islandia_games"))
        .isEqualTo("https://vk.ru.example.test/islandia_games");
  }
}
