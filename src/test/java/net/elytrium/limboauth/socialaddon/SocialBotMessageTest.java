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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.AbstractSocial;
import net.elytrium.limboauth.socialaddon.social.SocialButtonListenerAdapter;
import net.elytrium.limboauth.socialaddon.social.SocialMessageListenerAdapter;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

class SocialBotMessageTest {

  private static final String PROVIDER = SocialPlayer.TELEGRAM_DB_FIELD;
  private static final Long USER_ID = 73L;

  @TempDir
  Path directory;

  private Settings.MAIN settings;
  private Dao<SocialPlayer, String> dao;
  private SocialManager sender;
  private SocialMessageListenerAdapter commands;
  private List<List<AbstractSocial.ButtonItem>> keyboard;
  private SocialManager routing;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void prepare_settings_and_boundaries() throws Exception {
    Path file = this.directory.resolve("config.yml");
    Files.writeString(file, "main:\n  debug: false\n");
    Settings config = new Settings();
    config.reload(file.toFile(), "Islandia");
    this.settings = config.MAIN;
    Settings.IMP.MAIN = this.settings;
    this.dao = mock(Dao.class);
    this.sender = mock(SocialManager.class);
    this.commands = mock(SocialMessageListenerAdapter.class);
    this.keyboard = List.of(List.of(new AbstractSocial.ButtonItem("info", "Info", AbstractSocial.ButtonItem.Color.PRIMARY)));
  }

  @AfterEach
  void stop_local_routing_resources() {
    if (this.routing != null) {
      this.routing.stop();
    }
  }

  @Test
  void unknown_private_text_explains_linking_without_running_account_commands() throws Exception {
    this.listener().accept(PROVIDER, USER_ID, "Что умеет этот бот?");
    verify(this.sender).broadcastMessage(PROVIDER, USER_ID, this.settings.STRINGS.BOT_HELP + "\n\n" + this.settings.START_REPLY);
    verify(this.dao).queryForEq(PROVIDER, USER_ID);
    verifyNoMoreInteractions(this.dao, this.sender);
    verifyNoInteractions(this.commands);
  }

  @Test
  void linked_user_can_reopen_the_keyboard_with_menu_start_or_arbitrary_text() throws Exception {
    when(this.dao.queryForEq(PROVIDER, USER_ID)).thenReturn(List.of(new SocialPlayer("fixture")));
    SocialBotMessageListener listener = this.listener();
    listener.accept(PROVIDER, USER_ID, " МеНю ");
    listener.accept(PROVIDER, USER_ID, " НАЧАТЬ ");
    listener.accept(PROVIDER, USER_ID, "Привет");
    verify(this.sender, times(2))
        .broadcastMessage(PROVIDER, USER_ID, this.settings.STRINGS.KEYBOARD_RESTORED, this.keyboard);
    verify(this.sender).broadcastMessage(PROVIDER, USER_ID,
        this.settings.STRINGS.BOT_HELP + "\n\n" + this.settings.STRINGS.KEYBOARD_RESTORED, this.keyboard);
    verifyNoMoreInteractions(this.sender);
    verifyNoInteractions(this.commands);
  }

  @Test
  void mixed_case_start_is_recognized_for_an_unlinked_user() throws Exception {
    this.listener().accept(PROVIDER, USER_ID, " НаЧаТь ");
    verify(this.sender).broadcastMessage(PROVIDER, USER_ID, this.settings.START_REPLY);
    verifyNoInteractions(this.commands);
  }

  @Test
  void incomplete_or_complete_link_commands_are_handled_once_without_a_second_help_reply() throws Exception {
    SocialBotMessageListener listener = this.listener();
    listener.accept(PROVIDER, USER_ID, " !account link  ");
    listener.accept(PROVIDER, USER_ID, " !ACCOUNT LINK  Fixture  ");
    listener.accept(PROVIDER, USER_ID, "!account register Fixture");
    verify(this.commands).accept(PROVIDER, USER_ID, "!account link");
    verify(this.commands).accept(PROVIDER, USER_ID, "!ACCOUNT LINK  Fixture");
    verify(this.commands).accept(PROVIDER, USER_ID, "!account register Fixture");
    verifyNoMoreInteractions(this.commands);
    verifyNoInteractions(this.dao, this.sender);
  }

  @Test
  void command_prefix_typos_cannot_trigger_registration_or_linking() throws Exception {
    SocialBotMessageListener listener = this.listener();
    listener.accept(PROVIDER, USER_ID, "!account linked Fixture");
    listener.accept(PROVIDER, USER_ID, "!account registerOther Fixture");
    verify(this.sender, times(2)).broadcastMessage(PROVIDER, USER_ID,
        this.settings.STRINGS.BOT_HELP + "\n\n" + this.settings.START_REPLY);
    verifyNoInteractions(this.commands);
  }

  @Test
  void custom_commands_are_honored_without_enabling_the_default_command() throws Exception {
    this.settings.SOCIAL_LINK_CMDS = List.of("!connect");
    SocialBotMessageListener listener = this.listener();
    listener.accept(PROVIDER, USER_ID, "!connect Fixture");
    listener.accept(PROVIDER, USER_ID, "!account link Fixture");
    verify(this.commands).accept(PROVIDER, USER_ID, "!connect Fixture");
    verifyNoMoreInteractions(this.commands);
    verify(this.sender).broadcastMessage(PROVIDER, USER_ID, this.settings.STRINGS.BOT_HELP + "\n\n" + this.settings.START_REPLY);
    assertThat(SocialBotMessageListener.matchesCommand("!connect Fixture", "")).isFalse();
  }

  @Test
  void text_button_runs_only_its_action_and_a_removed_button_falls_back_to_help() throws Exception {
    this.routing = new SocialManager(mock(Logger.class), this.directory.resolve("status.json"));
    SocialButtonListenerAdapter button = mock(SocialButtonListenerAdapter.class);
    this.routing.registerKeyboard(this.keyboard);
    this.routing.addButtonEvent("info", button);
    this.routing.addMessageEvent(this.commands);
    this.routing.onMessageReceived(PROVIDER, USER_ID, "Info");
    verify(button).accept(PROVIDER, USER_ID);
    verifyNoInteractions(this.commands);
    this.routing.removeButtonEvent("info");
    this.routing.onMessageReceived(PROVIDER, USER_ID, "Info");
    verify(this.commands).accept(PROVIDER, USER_ID, "Info");
    verifyNoMoreInteractions(button);
  }

  @Test
  void failed_text_button_returns_one_safe_error_and_logs_sql_context_with_debug_disabled() {
    Logger logger = mock(Logger.class);
    this.routing = spy(new SocialManager(logger, this.directory.resolve("status.json")));
    this.routing.registerKeyboard(this.keyboard);
    this.routing.addMessageEvent(this.commands);
    this.routing.addButtonEvent("info", (provider, id) -> {
      throw new SQLException("Never log password=secret", "23505", 7);
    });
    this.routing.onMessageReceived(PROVIDER, USER_ID, "Info");
    verify(this.routing).broadcastMessage(PROVIDER, USER_ID, this.settings.STRINGS.SOCIAL_EXCEPTION_CAUGHT);
    verify(logger).warn("Social request failed provider={} operation={} reason={} sql_state={} sql_code={}",
        PROVIDER, "button", "SQLException", "23505", 7);
    verifyNoMoreInteractions(logger);
    verifyNoInteractions(this.commands);
  }

  @Test
  void failed_message_never_logs_private_content_and_stopped_router_ignores_more_messages() {
    Logger logger = mock(Logger.class);
    this.routing = spy(new SocialManager(logger, this.directory.resolve("status.json")));
    this.routing.addMessageEvent((provider, id, message) -> {
      throw new IllegalStateException("Never log token=secret");
    });
    this.routing.onMessageReceived(PROVIDER, USER_ID, "!account link Fixture secret");
    verify(logger).warn("Social request failed provider={} operation={} reason={} sql_state={} sql_code={}",
        PROVIDER, "message", "IllegalStateException", "none", 0);
    verifyNoMoreInteractions(logger);
    this.routing.stop();
    this.routing.addMessageEvent(this.commands);
    this.routing.onMessageReceived(PROVIDER, USER_ID, "ignored after stop");
    verifyNoInteractions(this.commands);
  }

  private SocialBotMessageListener listener() {
    return new SocialBotMessageListener(this.settings, () -> this.dao, this.sender, this.keyboard, this.commands);
  }
}
