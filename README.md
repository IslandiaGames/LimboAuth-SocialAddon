# Islandia SocialAddon

Fork of Elytrium LimboAuth SocialAddon, based on commit
`1bd5d1a332f4ffc42bc1c84299e4c00cbab7093d`. Version: `1.0.11-islandia.6`.

## Build and verification

Use JDK 21 and `./gradlew test check shadowJar`. The plugin retains Java 17 target
compatibility; the Islandia deployment runs Velocity on Java 25. The build keeps
the original fork's Checkstyle, SpotBugs and license checks. SpotBugs additionally
resolves the matching official LimboAPI runtime JAR for relocated types omitted
from the public API artifact; it is not packaged in SocialAddon.

## Account linking

Set `main.telegram.contact`, `main.discord.contact` and `main.vk.contact` to
explicit public HTTPS bot links, or leave them blank to discover the links from
the authenticated bot identity. Telegram uses getMe.username, Discord uses the
connected bot user ID, and VK uses the token community ID. Explicit valid links
take priority; invalid explicit links are rejected. Disabled services stay hidden.
Discovery reuses startup requests and never performs network I/O in a command.
Every authenticated player is invited once after the first real backend connection,
including registration, password login and restored authentication sessions.
The connection event only queues the invitation; an existing background task
reads links and sends it every two seconds. If bot identity is still loading,
the invitation waits up to two minutes. Exit, timeout and reload cancel pending invitations;
links are read again before sending and linked providers receive a green checkmark.
The announcement text uses the same lime color as LimboAuth login success.
White brackets remain distinct from the provider colors; stale database replies
cannot announce into a later login.
Server switches do not repeat the invitation; an empty retained SOCIAL row does
not suppress it. Buttons open the configured bot and explain the linking command
in their hover text. Modern clients receive brand RGB colors; pre-1.16 clients
receive distinct legacy colors. `/addtg`, `/addds` and `/addvk` without a code show
the corresponding contact, while `/addsocial` shows all configured contacts.
VK community links are normalized to vk.me message links. Discord profile links
include a hover instruction to select Message; a bot user ID is not a DM channel ID.
The existing challenge-code confirmation and opt-in 2FA remain unchanged.

Unknown or incomplete text commands receive help. Unlinked users get linking
instructions, linked users get the account keyboard. Commands ignore surrounding
whitespace and case; recognized text buttons are handled once. Unlinking one
provider preserves other links and security settings, refuses blocked or 2FA-enabled
accounts and runs side effects only after a successful database write.

Provider availability is reported by the runtime status file, not by the command
usage text. Startup logs list the enabled flags and report invalid or missing
public contacts. Failures log sanitized API codes or exception types, including
polling failures. Bot request errors include safe operation, SQLState and vendor
code independently of debug; raw responses and exception messages are never logged there.
Deploy the service-worded configuration together with the plugin sources.

Join and leave notifications read links and prepare messages on a dedicated
worker after backend connection/disconnection. Proxy events only submit work;
the queue holds at most 64 tasks and discards queued work older than 30 seconds.
Disabled notifications skip the read. Disconnect/reload invalidate pending join
notifications before any optional SQL. Reload/shutdown stop the worker and discard
late results. A blocked optional database call can still occupy that one worker
until the database driver returns; it cannot occupy the event caller.

Authentication checks for blocked accounts and enabled 2FA remain on the
authentication path. Blocked accounts cannot enter the passwordless confirmation
branch. Pending confirmations are consumed once and removed even when a player
leaves from Limbo without a backend connection. Reload/shutdown cancel remaining
confirmations; they never complete them as authenticated. Registration, response
consumption and stop share one ownership lock, so a late availability result
cannot publish a new waiting confirmation after shutdown. Duplicate pending
requests are rejected instead of replacing an existing waiting event.

## Provider lifecycle

Each enabled provider has its own startup/retry worker and bounded outgoing queue.
Authentication listeners and the database are installed before external clients
start. Provider failures never block proxy initialization. Retries back off from
5 to 60 seconds; Telegram/VK polling also retries independently. An unavailable
provider does not disable an account's stored 2FA setting or bypass authentication.
If no linked provider can receive the confirmation, login is cancelled clearly.
Messages queued for over 30 seconds are discarded. Reload/shutdown stop owned
workers and clients and reject old callbacks.

Telegram uses a validated HTTPS origin in `main.telegram.api-url`, defaulting to
`https://api.telegram.org`. All identity, polling, message and callback requests
use that origin. The Islandia Cloudflare relay supports the monitoring bot's
`BOT_TOKEN` and a separate account bot's `SOCIAL_BOT_TOKEN`. Webhooks are not
silently deleted; polling requires no configured webhook and one active poller.
The relay accepts the lowercase method names used by this SDK.

`ISLANDIA_SOCIAL_STATUS_FILE` selects an atomic JSON status file (default:
`plugins/limboauth-social-addon/status.json`). Version 1 contains `started_at`,
`updated_at` and exactly the fixed provider names `telegram`, `discord`, `vk`.
Each provider has a boolean `enabled` and a state `disabled`, `starting`,
`available` or `unavailable`. No tokens, contacts, account identifiers or raw
upstream exceptions are published. The snapshot updates every five seconds.
Telegram/VK availability requires successful polling within 90 seconds; Discord
requires a connected gateway. This reports integration health, including token,
relay and network errors, not a generic public website probe.

The accompanying infrastructure patch consumes this contract, exports metrics
and routes incidents through the existing Grafana problems and Alertmanager.

VK transport preserves HTTP status and Content-Type for the actual SDK response
parser. Local HTTP contract tests cover identity, API errors and HTTP failure.
The PostgreSQL unlink integration test uses a dedicated database configured by
`ISLANDIA_SOCIAL_TEST_JDBC_URL`; without it the test is explicitly skipped.

Real account linking, confirmation/rejection, password recovery and reconnection
must additionally be checked on a staging proxy with real bot credentials. Local
tests do not send requests to the providers or confirm real message delivery.

## Login latency verification

The provided log does not prove that VK caused a 15-second login. The VK polling
interruption occurred during shutdown at 22:21:02 UTC, before the observed join.
LimboFilter took 6.57 seconds (128 falling-check ticks already require 6.4 seconds).
The 38.97-second LimboAuth interval also contains human password/confirmation input;
the successful input timestamp is absent. Auth exit to lobby connection took
1.04 seconds. Neither the anti-bot delay nor mandatory authentication is shortened.

Trace these boundaries on staging with one connection identifier and monotonic
elapsed times, without recording passwords, bot tokens or confirmation contents:

| Boundary | Ownership and evidence needed |
| --- | --- |
| LimboFilter enter / exit | Anti-bot time; preserve the 128-tick check. |
| Password submitted / verified | LimboAuth password hashing and account DB read; exclude time spent typing. |
| Pre/post authorization social DAO | SocialAddon blocked-account and 2FA configuration reads remain required. |
| Confirmation requested / accepted / rejected | Enabled social 2FA must wait or cancel if unavailable; never proceed on a provider error. |
| Auth exit / initial server selection / backend connected | Separate proxy handoff from backend acceptance. |
| Optional join notification / link invitation | Background work after the first backend connection; never part of password verification. |

Focused local regressions cover blocked optional DAO reads, disconnect/reload with
late DAO replies, ordinary post-auth without 2FA, unavailable linked VK with 2FA,
blocked passwordless accounts, single-use confirmation, VK worker isolation and
cancellation of a stalled local HTTP request through the actual VK transport.
They do not verify LimboAuth password hashing, real PostgreSQL performance or a
live Velocity/backend connection. A live scenario must repeat password login,
registration and cached login with VK healthy, unavailable and stalled; then repeat
with mandatory 2FA and verify explicit approval/rejection and disconnected sessions.
The existing mock tests do not establish an end-to-end login latency improvement.

## Deployment

Build the fork with the pinned wrapper using JDK 21:
`bash gradlew test check shadowJar`. Copy
`build/libs/LimboAuth-SocialAddon-1.0.11-islandia.6.jar` into the disposable Velocity
template's `plugins` directory, together with the matching SocialAddon config.
The selector checks the actual plugin ID/version before removing previous copies.
Changing source/version files alone does not update the deployed plugin. No
SocialAddon JAR is bundled in the supplied source archive; a successful build and
artifact copy are required before deployment. Optional provider warnings remain
warnings; artifact identity checks and mandatory 2FA remain enforced.

## License

GNU Affero General Public License v3 or later; original copyright is retained.
See `LICENSE` and the headers of source files.
