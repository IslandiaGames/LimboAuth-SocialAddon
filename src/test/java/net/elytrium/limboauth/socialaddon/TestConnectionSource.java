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

import java.sql.Connection;
import java.sql.SQLException;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.jdbc.JdbcConnectionSource;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.jdbc.JdbcDatabaseConnection;
import net.elytrium.limboauth.thirdparty.com.j256.ormlite.jdbc.db.PostgresDatabaseType;

final class TestConnectionSource extends JdbcConnectionSource {

  TestConnectionSource(String url, Connection jdbc) throws SQLException {
    super(url, new PostgresDatabaseType());
    this.connection = new JdbcDatabaseConnection(jdbc);
  }
}
