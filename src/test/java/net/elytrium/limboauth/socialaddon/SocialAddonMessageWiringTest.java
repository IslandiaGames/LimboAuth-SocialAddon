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

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.social.SocialMessageListenerAdapter;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.slf4j.Logger;

class SocialAddonMessageWiringTest {

  @TempDir
  Path directory;

  @Test
  @SuppressWarnings("unchecked")
  void startup_and_reload_message_handlers_use_the_database_initialized_after_load() throws Exception {
    Files.writeString(this.directory.resolve("config.yml"), "main:\n  debug: false\n");
    Addon addon = mock(Addon.class);
    setField(addon, "dataDirectory", this.directory);
    setField(addon, "logger", mock(Logger.class));
    Method load = Addon.class.getDeclaredMethod("load");
    load.setAccessible(true);
    Dao<SocialPlayer, String> firstDao = mock(Dao.class);
    Dao<SocialPlayer, String> reloadedDao = mock(Dao.class);
    try (MockedConstruction<SocialManager> managers = mockConstruction(SocialManager.class)) {
      // onReload assembles callbacks before initializing the first DAO.
      load.invoke(addon);
      SocialManager firstManager = managers.constructed().get(0);
      SocialMessageListenerAdapter firstListener = listener(firstManager);
      setField(addon, "dao", firstDao);
      firstListener.accept(SocialPlayer.TELEGRAM_DB_FIELD, 73L, "/start");
      verify(firstManager).broadcastMessage(SocialPlayer.TELEGRAM_DB_FIELD, 73L, Settings.IMP.MAIN.START_REPLY);
      verify(firstDao).queryForEq(SocialPlayer.TELEGRAM_DB_FIELD, 73L);
      // During reload, load still sees the previous DAO until migration finishes.
      load.invoke(addon);
      SocialManager reloadedManager = managers.constructed().get(1);
      SocialMessageListenerAdapter reloadedListener = listener(reloadedManager);
      when(reloadedDao.queryForEq(SocialPlayer.TELEGRAM_DB_FIELD, 73L)).thenReturn(List.of(new SocialPlayer("fixture")));
      setField(addon, "dao", reloadedDao);
      reloadedListener.accept(SocialPlayer.TELEGRAM_DB_FIELD, 73L, "/start");
      verify(reloadedManager).broadcastMessage(eq(SocialPlayer.TELEGRAM_DB_FIELD), eq(73L),
          eq(Settings.IMP.MAIN.STRINGS.KEYBOARD_RESTORED), anyList());
      verify(reloadedDao).queryForEq(SocialPlayer.TELEGRAM_DB_FIELD, 73L);
      verifyNoMoreInteractions(firstDao, reloadedDao);
      verify(firstManager).stop();
    }
  }

  private static SocialMessageListenerAdapter listener(SocialManager manager) {
    ArgumentCaptor<SocialMessageListenerAdapter> listener = ArgumentCaptor.forClass(SocialMessageListenerAdapter.class);
    verify(manager).addMessageEvent(listener.capture());
    return listener.getValue();
  }

  private static void setField(Addon addon, String name, Object value) throws Exception {
    Field field = Addon.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(addon, value);
  }
}
