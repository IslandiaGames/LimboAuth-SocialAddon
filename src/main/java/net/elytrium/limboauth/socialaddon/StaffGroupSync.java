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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StaffGroupSync {

  private static final Logger LOGGER = LoggerFactory.getLogger(StaffGroupSync.class);

  private static final String PREFIX = "islandia-staff-";
  private static final String MARKER = "islandia.staff.managed-group";

  boolean exists(String name) {
    return LuckPermsProvider.get().getGroupManager().loadGroup(name).join().isPresent();
  }

  void apply(JsonObject snapshot) {
    LuckPerms api = LuckPermsProvider.get();
    for (var entry : snapshot.entrySet()) {
      try {
        this.syncUser(api, UUID.fromString(entry.getKey()), entry.getValue().getAsJsonArray());
      } catch (RuntimeException failure) {
        LOGGER.warn("Staff group synchronization failed for {}: {}", entry.getKey(), failure.getClass().getSimpleName());
      }
    }
  }

  private void syncUser(LuckPerms api, UUID id, JsonArray groups) {
    Set<String> desired = new HashSet<>();
    for (var value : groups) {
      String name = value.getAsString();
      if (!name.matches("[a-z0-9_][a-z0-9_-]{0,39}") || name.startsWith(PREFIX)) {
        throw new IllegalStateException("Invalid Staff group");
      }
      if (this.ensureGroup(api, name)) {
        desired.add(PREFIX + name);
      }
    }
    var user = api.getUserManager().loadUser(id).join();
    boolean changed = this.updateUser(user, desired, name ->
        api.getGroupManager().loadGroup(name).join().filter(this::managed).isPresent());
    if (changed) {
      api.getUserManager().saveUser(user).join();
    }
  }

  boolean updateUser(User user, Set<String> desired, Predicate<String> managed) {
    boolean changed = false;
    for (var node : user.getNodes()) {
      if (node instanceof InheritanceNode inherited && inherited.getGroupName().startsWith(PREFIX)
          && inherited.getValue() && !node.hasExpiry() && node.getContexts().isEmpty()) {
        String name = inherited.getGroupName();
        if (!desired.remove(name) && managed.test(name)) {
          changed |= user.data().remove(node).wasSuccessful();
        }
      }
    }
    for (String name : desired) {
      changed |= user.data().add(InheritanceNode.builder(name).build()).wasSuccessful();
    }
    return changed;
  }

  private boolean ensureGroup(LuckPerms api, String name) {
    var manager = api.getGroupManager();
    if (manager.loadGroup(name).join().isEmpty()) {
      return false;
    }
    String wrapper = PREFIX + name;
    var existing = manager.loadGroup(wrapper).join();
    if (existing.isPresent() && !this.managed(existing.get())) {
      LOGGER.warn("Cannot use existing unmanaged LuckPerms group {}", wrapper);
      return false;
    }
    var group = existing.orElseGet(() -> manager.createAndLoadGroup(wrapper).join());
    boolean changed = group.data().add(PermissionNode.builder(MARKER).build()).wasSuccessful();
    changed |= group.data().add(InheritanceNode.builder(name).build()).wasSuccessful();
    if (changed) {
      manager.saveGroup(group).join();
    }
    return true;
  }

  private boolean managed(Group group) {
    return group.getNodes().contains(PermissionNode.builder(MARKER).build());
  }
}
