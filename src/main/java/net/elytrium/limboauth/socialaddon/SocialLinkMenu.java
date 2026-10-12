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

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.elytrium.commons.kyori.serialization.Serializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

/** Renders configured public contacts independently of provider network startup. */
public final class SocialLinkMenu {

  private final Serializer serializer;
  private final List<Contact> contacts;
  private final String usage;
  private final String hover;
  private final Component announcement;
  private final Component notConfigured;

  public SocialLinkMenu(Settings.MAIN settings, Serializer serializer, Logger logger) {
    this.serializer = serializer;
    this.usage = settings.STRINGS.LINK_CMD_USAGE;
    this.hover = settings.STRINGS.LINK_CONTACT_HOVER;
    this.announcement = serializer.deserialize(settings.STRINGS.LINK_ANNOUNCEMENT);
    this.notConfigured = serializer.deserialize(settings.STRINGS.LINK_NOT_CONFIGURED);
    List<Contact> configured = new ArrayList<>();
    this.addContact(configured, Provider.TELEGRAM, settings.TELEGRAM.ENABLED, settings.TELEGRAM.CONTACT, logger);
    this.addContact(configured, Provider.DISCORD, settings.DISCORD.ENABLED, settings.DISCORD.CONTACT, logger);
    this.addContact(configured, Provider.VK, settings.VK.ENABLED, settings.VK.CONTACT, logger);
    this.contacts = List.copyOf(configured);
    if (this.contacts.isEmpty()) {
      logger.warn("Social account linking has no configured public contacts; check enabled and contact settings");
    }
  }

  private void addContact(List<Contact> result, Provider provider, boolean enabled, String contact, Logger logger) {
    if (!enabled) {
      return;
    }
    try {
      URI uri = new URI(contact.trim());
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
          || contact.length() > 2048) {
        logger.warn("Social provider {} has no valid public HTTPS contact; account linking is not advertised", provider.name());
        return;
      }
      result.add(new Contact(provider, uri.toASCIIString()));
    } catch (URISyntaxException error) {
      logger.warn("Social provider {} has an invalid public contact; account linking is not advertised", provider.name());
    }
  }

  public Optional<Component> announcement(Player player) {
    if (this.contacts.isEmpty() || this.announcement.equals(Component.empty())) {
      return Optional.empty();
    }
    return Optional.of(this.announcement.append(Component.newline()).append(Component.newline())
        .append(this.buttons(player, this.contacts)));
  }

  public Component usage(Player player, String alias) {
    List<Contact> selected = this.contacts.stream()
        .filter(contact -> Provider.fromAlias(alias).map(provider -> provider == contact.provider()).orElse(true)).toList();
    if (selected.isEmpty()) {
      return this.notConfigured;
    }
    return this.serializer.deserialize(this.usage.replace("{NICKNAME}", player.getUsername()))
        .append(Component.newline()).append(this.buttons(player, selected));
  }

  private Component buttons(Player player, List<Contact> selected) {
    Component result = Component.empty();
    boolean modern = player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_16) >= 0;
    for (Contact contact : selected) {
      if (!result.equals(Component.empty())) {
        result = result.append(Component.space());
      }
      Provider provider = contact.provider();
      Component button = Component.text("[" + provider.label + "]", modern ? provider.color : provider.legacyColor)
          .decorate(TextDecoration.BOLD)
          .clickEvent(ClickEvent.openUrl(contact.url()))
          .hoverEvent(this.serializer.deserialize(this.hover.replace("{NICKNAME}", player.getUsername())));
      result = result.append(button);
    }
    return result;
  }

  private record Contact(Provider provider, String url) {
  }

  private enum Provider {
    TELEGRAM("Telegram", "addtg", TextColor.color(0x2AABEE), NamedTextColor.AQUA),
    DISCORD("Discord", "addds", TextColor.color(0x5865F2), NamedTextColor.LIGHT_PURPLE),
    VK("VK", "addvk", TextColor.color(0x0077FF), NamedTextColor.BLUE);

    private final String label;
    private final String alias;
    private final TextColor color;
    private final NamedTextColor legacyColor;

    Provider(String label, String alias, TextColor color, NamedTextColor legacyColor) {
      this.label = label;
      this.alias = alias;
      this.color = color;
      this.legacyColor = legacyColor;
    }

    private static Optional<Provider> fromAlias(String alias) {
      for (Provider provider : values()) {
        if (provider.alias.equals(alias.toLowerCase(Locale.ROOT))) {
          return Optional.of(provider);
        }
      }
      return Optional.empty();
    }
  }
}
