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

package net.elytrium.limboauth.socialaddon.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.Player;
import java.util.List;
import net.elytrium.commons.kyori.serialization.Serializer;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboauth.event.PostAuthorizationEvent;
import net.elytrium.limboauth.event.TaskEvent;
import net.elytrium.limboauth.socialaddon.Addon;
import net.elytrium.limboauth.socialaddon.Settings;
import net.elytrium.limboauth.socialaddon.SocialManager;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LimboAuthAvailabilityTest {

  @Test
  @SuppressWarnings("unchecked")
  void unavailable_provider_never_bypasses_enabled_two_factor_authentication() throws Exception {
    Settings.IMP.MAIN = new Settings.MAIN();
    Settings.IMP.MAIN.STRINGS = new Settings.MAIN.STRINGS();
    Serializer serializer = mock(Serializer.class);
    when(serializer.deserialize(any(String.class))).thenReturn(Component.empty());
    try (MockedStatic<Addon> addon = mockStatic(Addon.class)) {
      addon.when(Addon::getSerializer).thenReturn(serializer);
      final SocialManager manager = mock(SocialManager.class);
      Player player = mock(Player.class);
      when(player.getUsername()).thenReturn("Fixture");
      LimboPlayer limboPlayer = mock(LimboPlayer.class);
      when(limboPlayer.getProxyPlayer()).thenReturn(player);
      SocialPlayer linked = new SocialPlayer("fixture");
      linked.setTotpEnabled(true);
      linked.setTelegramID(123L);
      Dao<SocialPlayer, String> dao = mock(Dao.class);
      when(dao.queryForId("fixture")).thenReturn(linked);
      when(dao.idExists("fixture")).thenReturn(true);
      PostAuthorizationEvent event = mock(PostAuthorizationEvent.class);
      when(event.getPlayer()).thenReturn(limboPlayer);
      LimboAuthListener listener = new LimboAuthListener(null, null, dao, manager, List.of(), null);
      listener.onAuthCompleted(event);
      verify(event).setResult(TaskEvent.Result.WAIT);
      verify(event).completeAndCancel(any(Component.class));
      verify(event, never()).complete(TaskEvent.Result.NORMAL);
      linked.setTotpEnabled(false);
      PostAuthorizationEvent ordinary = mock(PostAuthorizationEvent.class);
      when(ordinary.getPlayer()).thenReturn(limboPlayer);
      listener.onAuthCompleted(ordinary);
      verify(ordinary, never()).setResult(TaskEvent.Result.WAIT);
      verify(ordinary, never()).completeAndCancel(any(Component.class));
    }
  }
}
