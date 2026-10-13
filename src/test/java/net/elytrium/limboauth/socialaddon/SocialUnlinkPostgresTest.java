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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer.DatabaseField;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.DaoManager;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.jdbc.JdbcSingleConnectionSource;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.jdbc.db.PostgresDatabaseType;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.stmt.UpdateBuilder;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.table.TableUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(named = "ISLANDIA_SOCIAL_TEST_JDBC_URL",
    matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/islandia_social_unlink_test",
    disabledReason = "Requires a dedicated local PostgreSQL database named islandia_social_unlink_test")
class SocialUnlinkPostgresTest {

  private JdbcSingleConnectionSource source;
  private Dao<SocialPlayer, String> dao;
  private SocialUnlinkRepository repository;
  private boolean schemaCreated;

  @BeforeEach
  void createSchema() throws Exception {
    this.source = connectionSource();
    // SocialAddon owns its table through ORMLite, with the same creation path used by Addon.onReload.
    TableUtils.createTable(this.source, SocialPlayer.class);
    this.schemaCreated = true;
    this.dao = DaoManager.createDao(this.source, SocialPlayer.class);
    this.repository = new SocialUnlinkRepository(this.dao);
  }

  @AfterEach
  void removeSchema() throws Exception {
    if (this.source != null) {
      try {
        if (this.schemaCreated) {
          TableUtils.dropTable(this.source, SocialPlayer.class, false);
        }
      } finally {
        this.source.close();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(DatabaseField.class)
  void unlink_preserves_other_links_and_security_settings_after_reopening_the_connection(DatabaseField provider) throws Exception {
    SocialPlayer player = linkedPlayer();
    this.dao.create(player);
    assertThat(this.repository.unlink(player, provider, false)).isEqualTo(SocialUnlinkRepository.Result.ONE_UNLINKED);
    try (JdbcSingleConnectionSource reopened = connectionSource()) {
      Dao<SocialPlayer, String> reopenedDao = DaoManager.createDao(reopened, SocialPlayer.class);
      SocialPlayer actual = reopenedDao.queryForId(player.getLowercaseNickname());
      assertThat(provider.getIdFor(actual)).isNull();
      for (DatabaseField field : DatabaseField.values()) {
        if (field != provider) {
          assertThat(field.getIdFor(actual)).isEqualTo(field.getIdFor(player));
        }
      }
      assertThat(actual.isBlocked()).isFalse();
      assertThat(actual.isTotpEnabled()).isFalse();
      assertThat(actual.isNotifyEnabled()).isFalse();
    }
    assertThat(this.repository.unlink(player, provider, false)).isEqualTo(SocialUnlinkRepository.Result.STALE);
  }

  @Test
  void deleting_the_last_link_or_all_links_is_atomic_and_a_repeat_is_stale() throws Exception {
    SocialPlayer player = linkedPlayer();
    this.dao.create(player);
    assertThat(this.repository.unlink(player, DatabaseField.VK_ID, true)).isEqualTo(SocialUnlinkRepository.Result.ALL_UNLINKED);
    assertThat(this.repository.unlink(player, DatabaseField.VK_ID, true)).isEqualTo(SocialUnlinkRepository.Result.STALE);
    assertThat(this.dao.queryForId(player.getLowercaseNickname())).isNull();
    player.setVkID(null);
    player.setDiscordID(null);
    this.dao.create(player);
    assertThat(this.repository.unlink(player, DatabaseField.TELEGRAM_ID, false)).isEqualTo(SocialUnlinkRepository.Result.ALL_UNLINKED);
    assertThat(this.dao.queryForId(player.getLowercaseNickname())).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {SocialPlayer.BLOCKED_FIELD, SocialPlayer.TOTP_ENABLED_FIELD})
  void concurrent_security_change_prevents_both_partial_unlink_and_deletion(String field) throws Exception {
    SocialPlayer player = linkedPlayer();
    this.dao.create(player);
    this.changeColumn(field, true);
    assertThat(this.repository.unlink(player, DatabaseField.TELEGRAM_ID, false)).isEqualTo(SocialUnlinkRepository.Result.STALE);
    assertThat(this.repository.unlink(player, DatabaseField.TELEGRAM_ID, true)).isEqualTo(SocialUnlinkRepository.Result.STALE);
    SocialPlayer actual = this.dao.queryForId(player.getLowercaseNickname());
    assertThat(actual.getTelegramID()).isEqualTo(player.getTelegramID());
    SocialUnlinkRepository.Result expected = field.equals(SocialPlayer.BLOCKED_FIELD)
        ? SocialUnlinkRepository.Result.BLOCKED : SocialUnlinkRepository.Result.TWO_FACTOR_ENABLED;
    assertThat(this.repository.unlink(actual, DatabaseField.TELEGRAM_ID, true)).isEqualTo(expected);
  }

  @Test
  void stale_request_cannot_remove_a_new_link_or_a_changed_provider_owner() throws Exception {
    SocialPlayer player = linkedPlayer();
    player.setVkID(null);
    player.setDiscordID(null);
    this.dao.create(player);
    this.changeColumn(SocialPlayer.VK_DB_FIELD, 909L);
    assertThat(this.repository.unlink(player, DatabaseField.TELEGRAM_ID, false)).isEqualTo(SocialUnlinkRepository.Result.STALE);
    SocialPlayer oldOwner = this.dao.queryForId(player.getLowercaseNickname());
    this.changeColumn(SocialPlayer.TELEGRAM_DB_FIELD, 808L);
    assertThat(this.repository.unlink(oldOwner, DatabaseField.TELEGRAM_ID, false)).isEqualTo(SocialUnlinkRepository.Result.STALE);
    SocialPlayer actual = this.dao.queryForId(player.getLowercaseNickname());
    assertThat(actual.getVkID()).isEqualTo(909L);
    assertThat(actual.getTelegramID()).isEqualTo(808L);
  }

  @Test
  void two_competing_requests_have_one_successful_write() throws Exception {
    SocialPlayer player = linkedPlayer();
    this.dao.create(player);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<SocialUnlinkRepository.Result> first = executor.submit(() -> this.compete(ready, start));
      Future<SocialUnlinkRepository.Result> second = executor.submit(() -> this.compete(ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(SocialUnlinkRepository.Result.ONE_UNLINKED, SocialUnlinkRepository.Result.STALE);
      assertThat(this.dao.queryForId(player.getLowercaseNickname()).getDiscordID()).isNull();
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void database_write_failure_preserves_the_stored_links_and_allows_a_retry() throws Exception {
    SocialPlayer player = linkedPlayer();
    this.dao.create(player);
    Connection connection = this.source.getReadWriteConnection(SocialPlayer.TABLE_NAME).getUnderlyingConnection();
    connection.setReadOnly(true);
    connection.setAutoCommit(false);
    try {
      assertThatThrownBy(() -> this.repository.unlink(player, DatabaseField.DISCORD_ID, false)).isInstanceOf(SQLException.class);
    } finally {
      connection.rollback();
      connection.setAutoCommit(true);
      connection.setReadOnly(false);
    }
    assertThat(this.dao.queryForId(player.getLowercaseNickname()).getDiscordID()).isEqualTo(303L);
    assertThat(this.repository.unlink(player, DatabaseField.DISCORD_ID, false)).isEqualTo(SocialUnlinkRepository.Result.ONE_UNLINKED);
  }

  private SocialUnlinkRepository.Result compete(CountDownLatch ready, CountDownLatch start) throws Exception {
    try (JdbcSingleConnectionSource separate = connectionSource()) {
      Dao<SocialPlayer, String> separateDao = DaoManager.createDao(separate, SocialPlayer.class);
      SocialPlayer snapshot = separateDao.queryForId("unlink_test");
      ready.countDown();
      assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
      return new SocialUnlinkRepository(separateDao).unlink(snapshot, DatabaseField.DISCORD_ID, false);
    }
  }

  private void changeColumn(String field, Object value) throws SQLException {
    UpdateBuilder<SocialPlayer, String> update = this.dao.updateBuilder();
    update.where().eq(SocialPlayer.LOWERCASE_NICKNAME_FIELD, "unlink_test");
    update.updateColumnValue(field, value);
    assertThat(update.update()).isEqualTo(1);
  }

  private static JdbcSingleConnectionSource connectionSource() throws SQLException {
    String url = System.getenv("ISLANDIA_SOCIAL_TEST_JDBC_URL");
    String user = System.getenv().getOrDefault("ISLANDIA_SOCIAL_TEST_JDBC_USER", "social_test");
    String password = System.getenv().getOrDefault("ISLANDIA_SOCIAL_TEST_JDBC_PASSWORD", "");
    Connection connection = DriverManager.getConnection(url, user, password);
    try {
      return new JdbcSingleConnectionSource(url, new PostgresDatabaseType(), connection);
    } catch (SQLException exception) {
      connection.close();
      throw exception;
    }
  }

  private static SocialPlayer linkedPlayer() {
    SocialPlayer player = new SocialPlayer("unlink_test");
    player.setVkID(101L);
    player.setTelegramID(202L);
    player.setDiscordID(303L);
    player.setBlocked(false);
    player.setTotpEnabled(false);
    player.setNotifyEnabled(false);
    return player;
  }
}
