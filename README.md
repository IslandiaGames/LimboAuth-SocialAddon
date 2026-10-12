# Islandia SocialAddon

Fork of Elytrium LimboAuth SocialAddon, based on commit
`1bd5d1a332f4ffc42bc1c84299e4c00cbab7093d`. Version: `1.0.11-islandia.2`.

## Build and verification

Use JDK 21 and `./gradlew test check shadowJar`. The plugin retains Java 17 target
compatibility; the Islandia deployment runs Velocity on Java 25. The build keeps
the original fork's Checkstyle, SpotBugs and license checks. SpotBugs additionally
resolves the matching official LimboAPI runtime JAR for relocated types omitted
from the public API artifact; it is not packaged in SocialAddon.

## Account linking

Set `main.telegram.contact`, `main.discord.contact` and `main.vk.contact` to the
public HTTPS bot links. Only enabled providers with valid contacts are shown.
An unlinked player is invited once after the first real backend connection,
including registration, password login and restored authentication sessions.
Server switches do not repeat the invitation; an empty retained SOCIAL row does
not suppress it. Buttons open the configured bot and explain the linking command
in their hover text. Modern clients receive brand RGB colors; pre-1.16 clients
receive distinct legacy colors. `/addtg`, `/addds` and `/addvk` without a code show
the corresponding contact, while `/addsocial` shows all configured contacts.
The existing challenge-code confirmation and opt-in 2FA remain unchanged.

Provider availability is reported by the runtime status file, not by the command
usage text. Startup logs list the enabled flags and report invalid or missing
public contacts. Deploy the configuration together with the plugin sources.

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

Real account linking, confirmation/rejection, password recovery and reconnection
must additionally be checked on a staging proxy with real bot credentials. Local
tests do not send requests to the providers or confirm real message delivery.

## License

GNU Affero General Public License v3 or later; original copyright is retained.
See `LICENSE` and the headers of source files.
