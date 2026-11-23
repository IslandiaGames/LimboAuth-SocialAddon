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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.junit.jupiter.api.Test;

class StaffGroupSyncTest {

  @Test
  void only_removes_the_last_staff_grant_and_preserves_paid_independent_and_scoped_nodes() {
    var data = mock(NodeMap.class);
    var user = mock(User.class);
    var staff = parent("islandia-staff-admin");
    var independent = parent("admin");
    var paid = parent("premium");
    var reserved = parent("islandia-staff-custom");
    var temporary = parent("islandia-staff-admin");
    when(temporary.hasExpiry()).thenReturn(true);
    when(user.getNodes()).thenReturn(List.of(staff, independent, paid, reserved, temporary));
    when(user.data()).thenReturn(data);
    when(data.remove(staff)).thenReturn(DataMutateResult.SUCCESS);
    var sync = new StaffGroupSync();
    assertThat(sync.updateUser(user, new HashSet<>(Set.of("islandia-staff-admin")), "islandia-staff-admin"::equals)).isFalse();
    verify(data, never()).remove(staff);
    assertThat(sync.updateUser(user, new HashSet<>(), "islandia-staff-admin"::equals)).isTrue();
    verify(data).remove(staff);
    for (var node : List.of(independent, paid, temporary, reserved)) {
      verify(data, never()).remove(node);
    }
  }

  private static InheritanceNode parent(String name) {
    var node = mock(InheritanceNode.class);
    var contexts = mock(ImmutableContextSet.class);
    when(contexts.isEmpty()).thenReturn(true);
    when(node.getContexts()).thenReturn(contexts);
    when(node.getValue()).thenReturn(true);
    when(node.getGroupName()).thenReturn(name);
    return node;
  }
}
