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

import com.velocitypowered.api.proxy.Player;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.elytrium.commons.kyori.serialization.Serializer;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.SocialContactLinks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

/** Resolves public links without network requests on the command or login path. */
public final class SocialLinkMenu {

  private final Serializer serializer;
  private final List<Contact> contacts;
  private final Function<String, Optional<String>> discoveredContacts;
  private final String usage;
  private final String hover;
  private final String discordHover;
  private final Component announcement;
  private final Component notConfigured;

  public SocialLinkMenu(Settings.MAIN settings, Serializer serializer, Logger logger,
                        Function<String, Optional<String>> discoveredContacts) {
    this.serializer = serializer;
    this.discoveredContacts = discoveredContacts;
    this.usage = settings.STRINGS.LINK_CMD_USAGE;
    this.hover = settings.STRINGS.LINK_CONTACT_HOVER;
    this.discordHover = settings.STRINGS.LINK_DISCORD_CONTACT_HOVER;
    this.announcement = serializer.deserialize(settings.STRINGS.LINK_ANNOUNCEMENT);
    this.notConfigured = serializer.deserialize(settings.STRINGS.LINK_NOT_CONFIGURED);
    List<Contact> configured = new ArrayList<>();
    this.addContact(configured, Provider.TELEGRAM, settings.TELEGRAM.ENABLED, settings.TELEGRAM.CONTACT, logger);
    this.addContact(configured, Provider.DISCORD, settings.DISCORD.ENABLED, settings.DISCORD.CONTACT, logger);
    this.addContact(configured, Provider.VK, settings.VK.ENABLED, settings.VK.CONTACT, logger);
    this.contacts = List.copyOf(configured);
    if (this.contacts.isEmpty()) {
      logger.warn("Social account linking has no enabled services with configured or discoverable contacts");
    }
  }

  private void addContact(List<Contact> result, Provider provider, boolean enabled, String contact, Logger logger) {
    if (!enabled) {
      return;
    }
    if (contact.isBlank()) {
      result.add(new Contact(provider, ""));
      logger.info("Social provider {} public contact will be discovered from the bot identity", provider.name());
      return;
    }
    try {
      URI uri = new URI(contact.trim());
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
          || contact.length() > 2048) {
        logger.warn("Social provider {} has no valid public HTTPS contact; account linking is not advertised", provider.name());
        return;
      }
      result.add(new Contact(provider, this.messageContact(provider, uri.toASCIIString())));
    } catch (URISyntaxException error) {
      logger.warn("Social provider {} has an invalid public contact; account linking is not advertised", provider.name());
    }
  }

  public Optional<Component> announcement(Player player) {
    return this.announcement(player, Set.of());
  }

  public Optional<Component> announcement(Player player, Set<SocialPlayer.DatabaseField> linkedServices) {
    List<Contact> resolved = this.resolveContacts();
    if (resolved.isEmpty() || this.announcement.equals(Component.empty())) {
      return Optional.empty();
    }
    return Optional.of(this.announcement.append(Component.newline()).append(Component.newline())
        .append(this.buttons(player, resolved, linkedServices)));
  }

  public boolean isAnnouncementAvailable() {
    return !this.announcement.equals(Component.empty()) && !this.resolveContacts().isEmpty();
  }

  public Component usage(Player player, String alias) {
    List<Contact> selected = this.resolveContacts().stream()
        .filter(contact -> Provider.fromAlias(alias).map(provider -> provider == contact.provider()).orElse(true)).toList();
    if (selected.isEmpty()) {
      return this.notConfigured;
    }
    return this.serializer.deserialize(this.usage.replace("{NICKNAME}", player.getUsername()))
        .append(Component.newline()).append(this.buttons(player, selected, Set.of()));
  }

  private List<Contact> resolveContacts() {
    List<Contact> resolved = new ArrayList<>();
    for (Contact contact : this.contacts) {
      if (!contact.url().isEmpty()) {
        resolved.add(contact);
      } else {
        this.discoveredContacts.apply(contact.provider().dbField.name())
            .ifPresent(url -> resolved.add(new Contact(contact.provider(), this.messageContact(contact.provider(), url))));
      }
    }
    return resolved;
  }

  private String messageContact(Provider provider, String url) {
    return provider == Provider.VK ? SocialContactLinks.vkMessages(url) : url;
  }

  private Component buttons(Player player, List<Contact> selected, Set<SocialPlayer.DatabaseField> linkedServices) {
    Component result = Component.empty();
    for (Contact contact : selected) {
      if (!result.equals(Component.empty())) {
        result = result.append(Component.space());
      }
      Provider provider = contact.provider();
      Component label = Component.text(provider.label, provider.color);
      if (linkedServices.contains(provider.dbField)) {
        label = label.append(Component.text(" ✓", NamedTextColor.GREEN));
      }
      Component button = Component.text("[", NamedTextColor.WHITE)
          .append(label).append(Component.text("]", NamedTextColor.WHITE))
          .decorate(TextDecoration.BOLD)
          .clickEvent(ClickEvent.openUrl(contact.url()))
          .hoverEvent(this.serializer.deserialize((provider == Provider.DISCORD ? this.discordHover : this.hover)
              .replace("{NICKNAME}", player.getUsername())));
      result = result.append(button);
    }
    return result;
  }

  private record Contact(Provider provider, String url) {
  }

  private enum Provider {
    TELEGRAM("Telegram", "addtg", SocialPlayer.DatabaseField.TELEGRAM_ID, NamedTextColor.AQUA),
    DISCORD("Discord", "addds", SocialPlayer.DatabaseField.DISCORD_ID, NamedTextColor.BLUE),
    VK("VK", "addvk", SocialPlayer.DatabaseField.VK_ID, NamedTextColor.DARK_BLUE);

    private final String label;
    private final String alias;
    private final SocialPlayer.DatabaseField dbField;
    private final NamedTextColor color;

    Provider(String label, String alias, SocialPlayer.DatabaseField dbField, NamedTextColor color) {
      this.label = label;
      this.alias = alias;
      this.dbField = dbField;
      this.color = color;
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
