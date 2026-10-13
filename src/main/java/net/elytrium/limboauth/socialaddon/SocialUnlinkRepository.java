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

import java.sql.SQLException;
import java.util.Arrays;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer;
import net.elytrium.limboauth.socialaddon.model.SocialPlayer.DatabaseField;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.dao.Dao;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.field.SqlType;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.stmt.DeleteBuilder;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.stmt.SelectArg;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.stmt.UpdateBuilder;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.stmt.Where;

public final class SocialUnlinkRepository {

  private final Dao<SocialPlayer, String> dao;

  public SocialUnlinkRepository(Dao<SocialPlayer, String> dao) {
    this.dao = dao;
  }

  public Result unlink(SocialPlayer player, DatabaseField provider, boolean unlinkAll) throws SQLException {
    if (player.isBlocked()) {
      return Result.BLOCKED;
    }
    if (player.isTotpEnabled()) {
      return Result.TWO_FACTOR_ENABLED;
    }
    if (provider.getIdFor(player) == null) {
      return Result.STALE;
    }

    boolean removeAccount = unlinkAll || Arrays.stream(DatabaseField.values())
        .filter(field -> field != provider).allMatch(field -> field.getIdFor(player) == null);
    if (removeAccount) {
      DeleteBuilder<SocialPlayer, String> builder = this.dao.deleteBuilder();
      this.requireUnchangedAccount(builder.where(), player);
      return builder.delete() == 1 ? Result.ALL_UNLINKED : Result.STALE;
    }

    UpdateBuilder<SocialPlayer, String> builder = this.dao.updateBuilder();
    this.requireUnchangedAccount(builder.where(), player);
    // ORMLite's shared null holder binds VARCHAR, which PostgreSQL rejects for BIGINT IDs.
    builder.updateColumnValue(provider.name(), new SelectArg(SqlType.LONG, null));
    return builder.update() == 1 ? Result.ONE_UNLINKED : Result.STALE;
  }

  private void requireUnchangedAccount(Where<SocialPlayer, String> where, SocialPlayer player) throws SQLException {
    where.eq(SocialPlayer.LOWERCASE_NICKNAME_FIELD, player.getLowercaseNickname())
        .and().eq(SocialPlayer.BLOCKED_FIELD, false)
        .and().eq(SocialPlayer.TOTP_ENABLED_FIELD, false);
    // Compare every link so a delayed request cannot delete a newly linked provider or changed owner.
    for (DatabaseField field : DatabaseField.values()) {
      where.and();
      Long id = field.getIdFor(player);
      if (id == null) {
        where.isNull(field.name());
      } else {
        where.eq(field.name(), id);
      }
    }
  }

  public enum Result {
    ONE_UNLINKED,
    ALL_UNLINKED,
    BLOCKED,
    TWO_FACTOR_ENABLED,
    STALE
  }
}
