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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer.DatabaseField;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.DaoManager;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.jdbc.JdbcSingleConnectionSource;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.jdbc.db.PostgresDatabaseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;

class SocialUnlinkRepositoryTest {

  @ParameterizedTest
  @EnumSource(DatabaseField.class)
  void unlink_binds_a_bigint_null_through_real_ormlite_and_jdbc_without_mutating_the_snapshot(DatabaseField provider)
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      SocialPlayer player = linkedPlayer();
      assertThat(fixture.repository.unlink(player, provider, false)).isEqualTo(SocialUnlinkRepository.Result.ONE_UNLINKED);
      verify(fixture.statement).setNull(1, Types.BIGINT);
      verify(fixture.statement, never()).setNull(1, Types.VARCHAR);
      assertThat(player.getVkID()).isEqualTo(101L);
      assertThat(player.getTelegramID()).isEqualTo(202L);
      assertThat(player.getDiscordID()).isEqualTo(303L);
    }
  }

  @Test
  void blocked_and_two_factor_accounts_are_refused_before_any_write() throws Exception {
    try (Fixture fixture = new Fixture()) {
      SocialPlayer player = linkedPlayer();
      player.setBlocked(true);
      assertThat(fixture.repository.unlink(player, DatabaseField.TELEGRAM_ID, true))
          .isEqualTo(SocialUnlinkRepository.Result.BLOCKED);
      player.setBlocked(false);
      player.setTotpEnabled(true);
      assertThat(fixture.repository.unlink(player, DatabaseField.TELEGRAM_ID, false))
          .isEqualTo(SocialUnlinkRepository.Result.TWO_FACTOR_ENABLED);
      verify(fixture.statement, never()).executeUpdate();
    }
  }

  @Test
  void failed_or_stale_writes_never_return_success() throws Exception {
    try (Fixture fixture = new Fixture()) {
      when(fixture.statement.executeUpdate()).thenReturn(0);
      SocialPlayer player = linkedPlayer();
      assertThat(fixture.repository.unlink(player, DatabaseField.DISCORD_ID, false))
          .isEqualTo(SocialUnlinkRepository.Result.STALE);
      when(fixture.statement.executeUpdate()).thenThrow(new SQLException("Synthetic write failure"));
      assertThatThrownBy(() -> fixture.repository.unlink(player, DatabaseField.DISCORD_ID, false))
          .isInstanceOf(SQLException.class);
      assertThat(player.getDiscordID()).isEqualTo(303L);
    }
  }

  private static SocialPlayer linkedPlayer() {
    SocialPlayer player = new SocialPlayer("unlink_test");
    player.setVkID(101L);
    player.setTelegramID(202L);
    player.setDiscordID(303L);
    player.setBlocked(false);
    player.setTotpEnabled(false);
    return player;
  }

  private static final class Fixture implements AutoCloseable {

    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final JdbcSingleConnectionSource source;
    private final SocialUnlinkRepository repository;

    private Fixture() throws SQLException {
      Connection connection = mock(Connection.class, invocation -> {
        if (invocation.getMethod().getReturnType() == PreparedStatement.class) {
          return this.statement;
        }
        return Answers.RETURNS_DEFAULTS.answer(invocation);
      });
      try {
        when(this.statement.executeUpdate()).thenReturn(1);
        this.source = new JdbcSingleConnectionSource("jdbc:postgresql://localhost/unlink_test", new PostgresDatabaseType(), connection);
        Dao<SocialPlayer, String> dao = DaoManager.createDao(this.source, SocialPlayer.class);
        this.repository = new SocialUnlinkRepository(dao);
      } catch (SQLException | RuntimeException exception) {
        try {
          this.statement.close();
        } finally {
          connection.close();
        }
        throw exception;
      }
    }

    @Override
    public void close() throws SQLException {
      try {
        this.statement.close();
      } finally {
        this.source.close();
      }
    }
  }
}
