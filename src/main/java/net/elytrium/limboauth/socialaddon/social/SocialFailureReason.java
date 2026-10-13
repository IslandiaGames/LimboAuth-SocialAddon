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

package net.elytrium.limboauth.socialaddon.social;

import com.vk.api.sdk.exceptions.ApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

/** Exposes API codes and exception types without messages, URLs or credentials. */
public final class SocialFailureReason {

  private SocialFailureReason() {
  }

  public static String describe(Throwable error) {
    Throwable current = error;
    for (int depth = 0; depth < 8; depth++) {
      if (current instanceof ApiException vk && vk.getCode() != null) {
        return "vk_api_" + vk.getCode();
      }
      if (current instanceof TelegramApiRequestException telegram && telegram.getErrorCode() != null) {
        return "telegram_api_" + telegram.getErrorCode();
      }
      Throwable cause = current.getCause();
      if (cause == null || cause == current) {
        break;
      }
      current = cause;
    }
    return current.getClass().getSimpleName();
  }
}
