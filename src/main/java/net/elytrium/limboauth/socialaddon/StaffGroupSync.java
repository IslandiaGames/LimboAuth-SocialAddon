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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StaffGroupSync {

  private static final Logger LOGGER = LoggerFactory.getLogger(StaffGroupSync.class);

  private static final String PREFIX = "islandia-staff-";
  private static final String MARKER = "islandia.staff.managed-group";

  boolean exists(String name) {
    return assignable(name) && LuckPermsProvider.get().getGroupManager().loadGroup(name).join().isPresent();
  }

  void apply(JsonObject snapshot) {
    LuckPerms api = LuckPermsProvider.get();
    // Parse the entire successful response before removing obsolete memberships.
    var desiredPlayers = new HashMap<UUID, JsonArray>();
    for (var entry : snapshot.entrySet()) {
      desiredPlayers.put(UUID.fromString(entry.getKey()), entry.getValue().getAsJsonArray());
    }
    var actual = api.getUserManager().searchAll(NodeMatcher.keyStartsWith("group." + PREFIX)).join();
    var empty = new JsonArray();
    for (UUID id : actual.keySet()) {
      desiredPlayers.putIfAbsent(id, empty);
    }
    Set<String> prepared = new HashSet<>();
    Set<String> managed = new HashSet<>();
    for (var entry : desiredPlayers.entrySet()) {
      try {
        this.syncUser(api, entry.getKey(), entry.getValue(), actual.getOrDefault(entry.getKey(), List.of()), prepared, managed);
      } catch (RuntimeException failure) {
        LOGGER.warn("Staff group synchronization failed for {}: {}", entry.getKey(), failure.getClass().getSimpleName());
      }
    }
  }

  private void syncUser(LuckPerms api, UUID id, JsonArray groups, Collection<Node> actual, Set<String> prepared, Set<String> managed) {
    Set<String> desired = new HashSet<>();
    for (var value : groups) {
      String name = value.getAsString();
      if (!assignable(name)) {
        throw new IllegalStateException("Invalid Staff group");
      }
      if (prepared.contains(name) || this.ensureGroup(api, name)) {
        prepared.add(name);
        String wrapper = wrapperName(name);
        managed.add(wrapper);
        desired.add(wrapper);
      }
    }
    Predicate<String> isManaged = name -> {
      if (managed.contains(name)) {
        return true;
      }
      if (api.getGroupManager().loadGroup(name).join().filter(this::managed).isEmpty()) {
        return false;
      }
      managed.add(name);
      return true;
    };
    if (alreadyApplied(actual, desired, isManaged)) {
      return;
    }
    var user = api.getUserManager().loadUser(id).join();
    boolean changed = this.updateUser(user, desired, isManaged);
    if (changed) {
      api.getUserManager().saveUser(user).join();
    }
  }

  static boolean alreadyApplied(Collection<? extends Node> nodes, Set<String> desired, Predicate<String> managed) {
    Set<String> actual = new HashSet<>();
    for (var node : nodes) {
      if (node instanceof InheritanceNode parent && ownedCandidate(parent) && managed.test(parent.getGroupName())) {
        actual.add(parent.getGroupName());
      }
    }
    return actual.equals(desired);
  }

  private static boolean ownedCandidate(InheritanceNode node) {
    return node.getGroupName().startsWith(PREFIX) && node.getValue() && !node.hasExpiry() && node.getContexts().isEmpty();
  }

  boolean updateUser(User user, Set<String> desired, Predicate<String> managed) {
    boolean changed = false;
    for (var node : user.getNodes()) {
      if (node instanceof InheritanceNode inherited && ownedCandidate(inherited)) {
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

  private static boolean assignable(String name) {
    return !name.isEmpty() && name.length() <= 36 && name.chars().noneMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch))
        && !name.startsWith(PREFIX) && !name.startsWith("islandia-store-");
  }

  static String wrapperName(String name) {
    // Keep every original wrapper that fits in LuckPerms' 36-character storage key.
    if (name.matches("[a-z0-9_][a-z0-9_-]{0,20}")) {
      return PREFIX + name;
    }
    try {
      var hash = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
      return PREFIX + "~" + HexFormat.of().formatHex(hash, 0, 10);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private boolean ensureGroup(LuckPerms api, String name) {
    var manager = api.getGroupManager();
    if (manager.loadGroup(name).join().isEmpty()) {
      return false;
    }
    String wrapper = wrapperName(name);
    var existing = manager.loadGroup(wrapper).join();
    if (existing.isPresent() && !this.managed(existing.get())) {
      LOGGER.warn("Cannot use existing unmanaged LuckPerms group {}", wrapper);
      return false;
    }
    if (wrapper.startsWith(PREFIX + "~") && existing.isPresent() && existing.get().getNodes().stream()
        .anyMatch(node -> node instanceof InheritanceNode parent && !parent.getGroupName().equals(name))) {
      LOGGER.warn("Conflicting managed LuckPerms group {}", wrapper);
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
