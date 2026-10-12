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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

class SocialStatusWriterTest {

  @TempDir
  Path directory;

  @Test
  void publishes_fixed_states_without_credentials_and_stops_publishing_on_close() throws Exception {
    SocialRuntimeTest.FakeSocial social = new SocialRuntimeTest.FakeSocial();
    SocialRuntime runtime = new SocialRuntime("telegram", social, mock(Logger.class));
    Path path = this.directory.resolve("status.json");
    SocialStatusWriter writer = new SocialStatusWriter(path, List.of(runtime), mock(Logger.class),
        Clock.fixed(Instant.ofEpochSecond(12345), ZoneOffset.UTC));
    try {
      writer.publish();
      JsonObject snapshot = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
      assertThat(snapshot.get("version").getAsInt()).isEqualTo(1);
      assertThat(snapshot.get("updated_at").getAsLong()).isEqualTo(12345);
      assertThat(snapshot.getAsJsonArray("providers").get(0).getAsJsonObject().get("state").getAsString()).isEqualTo("starting");
      runtime.tick();
      writer.publish();
      String available = Files.readString(path);
      assertThat(available).contains("available").doesNotContain("token", "contact", "secret");
      writer.stop();
      runtime.stop();
      writer.publish();
      assertThat(Files.readString(path)).isEqualTo(available);
      assertThat(Files.exists(path.resolveSibling("status.json.tmp"))).isFalse();
    } finally {
      writer.stop();
      runtime.stop();
    }
  }
}
